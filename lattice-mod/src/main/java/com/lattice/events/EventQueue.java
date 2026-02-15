package com.lattice.events;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.lattice.Lattice;
import com.lattice.config.LatticeConfig;
import com.lattice.http.BackendClient;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;

public class EventQueue {
    private static final Logger LOGGER = Lattice.LOGGER;
    private static final Gson GSON = new GsonBuilder().serializeNulls().create();
    private static final String INGEST_PATH = "/v2/ingest/events";

    private final LatticeConfig config;
    private final Queue<EventPayload> buffer = new ConcurrentLinkedQueue<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "lattice-sender");
        thread.setDaemon(true);
        return thread;
    });

    private MinecraftServer server;

    public EventQueue(LatticeConfig config) {
        this.config = config;
    }

    public void start(MinecraftServer server) {
        this.server = server;
        scheduler.scheduleAtFixedRate(this::flush, config.flushIntervalMs, config.flushIntervalMs, TimeUnit.MILLISECONDS);
        LOGGER.info("Event queue started, backend {}", config.backendUrl);
    }

    public void enqueue(EventPayload payload) {
        buffer.add(payload);
    }

    public void shutdown() {
        scheduler.shutdownNow();
    }

    public MinecraftServer getServer() {
        return server;
    }

    private void flush() {
        try {
            resendSpool();
            if (buffer.isEmpty()) {
                return;
            }
            List<EventPayload> batch = new ArrayList<>();
            for (int i = 0; i < config.batchSize; i++) {
                EventPayload payload = buffer.poll();
                if (payload == null) {
                    break;
                }
                batch.add(payload);
            }
            if (batch.isEmpty()) {
                return;
            }
            byte[] body = buildEnvelope(batch);
            if (!send(body)) {
                spool(body);
            }
        } catch (Exception e) {
            LOGGER.warn("Flush failed", e);
        }
    }

    private byte[] buildEnvelope(List<EventPayload> events) {
        IngestEnvelope envelope = new IngestEnvelope("v2", config.serverId, events);
        return GSON.toJson(envelope).getBytes(StandardCharsets.UTF_8);
    }

    private boolean send(byte[] raw) {
        try {
            byte[] gzipped = gzip(raw);
            HttpRequest.Builder builder = BackendClient.request(config, INGEST_PATH, Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("Content-Encoding", "gzip")
                .POST(HttpRequest.BodyPublishers.ofByteArray(gzipped));
            HttpResponse<String> response = BackendClient.client().send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return true;
            }
            LOGGER.warn("Backend responded with {}", response.statusCode());
        } catch (Exception e) {
            LOGGER.warn("Failed to send batch", e);
        }
        return false;
    }

    private void spool(byte[] raw) {
        try {
            Files.createDirectories(config.spoolDir);
            String fileName = System.currentTimeMillis() + "-" + UUID.randomUUID() + ".json.gz";
            Path path = config.spoolDir.resolve(fileName);
            Files.write(path, gzip(raw));
        } catch (IOException e) {
            LOGGER.warn("Failed to spool batch", e);
        }
    }

    private void resendSpool() {
        if (!Files.exists(config.spoolDir)) {
            return;
        }
        try (Stream<Path> stream = Files.list(config.spoolDir)) {
            stream
                .filter(path -> path.getFileName().toString().endsWith(".json.gz"))
                .limit(5)
                .forEach(path -> {
                    try {
                        byte[] data = Files.readAllBytes(path);
                        if (sendGzip(data)) {
                            Files.deleteIfExists(path);
                        }
                    } catch (IOException e) {
                        LOGGER.warn("Failed to resend spool {}", path, e);
                    }
                });
        } catch (IOException e) {
            LOGGER.warn("Failed to list spool dir", e);
        }
    }

    private boolean sendGzip(byte[] gzipped) {
        try {
            HttpRequest.Builder builder = BackendClient.request(config, INGEST_PATH, Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("Content-Encoding", "gzip")
                .POST(HttpRequest.BodyPublishers.ofByteArray(gzipped));
            HttpResponse<String> response = BackendClient.client().send(builder.build(), HttpResponse.BodyHandlers.ofString());
            return response.statusCode() >= 200 && response.statusCode() < 300;
        } catch (Exception e) {
            LOGGER.warn("Failed to resend spool batch", e);
            return false;
        }
    }

    private byte[] gzip(byte[] raw) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(raw);
        }
        return out.toByteArray();
    }

    private record IngestEnvelope(
        String schema_version,
        String server_id,
        List<EventPayload> events
    ) {
    }
}
