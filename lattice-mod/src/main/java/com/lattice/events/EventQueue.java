package com.lattice.events;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.lattice.Lattice;
import com.lattice.config.LatticeConfig;
import com.lattice.http.BackendClient;
import com.lattice.logging.StructuredOpsLogger;
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
import java.util.Map;
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

    private volatile LatticeConfig config;
    private final Queue<EventPayload> buffer = new ConcurrentLinkedQueue<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "lattice-sender");
        thread.setDaemon(true);
        return thread;
    });

    private MinecraftServer server;
    private volatile long nextFlushAtMs;

    public EventQueue(LatticeConfig config) {
        this.config = config;
    }

    public void start(MinecraftServer server) {
        this.server = server;
        long now = System.currentTimeMillis();
        nextFlushAtMs = now + Math.max(200L, config.flushIntervalMs);
        scheduler.scheduleAtFixedRate(this::flushIfDue, 250L, 250L, TimeUnit.MILLISECONDS);
        LOGGER.info("Event queue started, backend {}", config.backendUrl);
        StructuredOpsLogger.info(
            "event_queue_started",
            Map.of("backend_url", config.backendUrl, "flush_interval_ms", config.flushIntervalMs)
        );
    }

    public void updateConfig(LatticeConfig next) {
        if (next == null) {
            return;
        }
        this.config = next;
        StructuredOpsLogger.info(
            "event_queue_config_updated",
            Map.of("flush_interval_ms", next.flushIntervalMs, "batch_size", next.batchSize)
        );
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

    private void flushIfDue() {
        long now = System.currentTimeMillis();
        if (now < nextFlushAtMs) {
            return;
        }
        LatticeConfig snapshot = this.config;
        nextFlushAtMs = now + Math.max(200L, snapshot.flushIntervalMs);
        flush(snapshot);
    }

    private void flush(LatticeConfig snapshot) {
        try {
            resendSpool(snapshot);
            if (buffer.isEmpty()) {
                return;
            }
            List<EventPayload> batch = new ArrayList<>();
            for (int i = 0; i < snapshot.batchSize; i++) {
                EventPayload payload = buffer.poll();
                if (payload == null) {
                    break;
                }
                batch.add(payload);
            }
            if (batch.isEmpty()) {
                return;
            }
            byte[] body = buildEnvelope(snapshot, batch);
            if (!send(snapshot, body)) {
                spool(snapshot, body);
            }
        } catch (Exception e) {
            LOGGER.warn("Flush failed", e);
            StructuredOpsLogger.warn("event_flush_failed", Map.of("error", e.toString()));
        }
    }

    private byte[] buildEnvelope(LatticeConfig snapshot, List<EventPayload> events) {
        IngestEnvelope envelope = new IngestEnvelope("v2", snapshot.serverId, events);
        return GSON.toJson(envelope).getBytes(StandardCharsets.UTF_8);
    }

    private boolean send(LatticeConfig snapshot, byte[] raw) {
        try {
            byte[] gzipped = gzip(raw);
            HttpRequest.Builder builder = BackendClient.request(snapshot, INGEST_PATH, Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("Content-Encoding", "gzip")
                .POST(HttpRequest.BodyPublishers.ofByteArray(gzipped));
            HttpResponse<String> response = BackendClient.client().send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                StructuredOpsLogger.debug("event_flush_ok", Map.of("status", response.statusCode(), "bytes", raw.length));
                return true;
            }
            LOGGER.warn("Backend responded with {}", response.statusCode());
            StructuredOpsLogger.warn(
                "event_flush_backend_rejected",
                Map.of("status", response.statusCode(), "bytes", raw.length)
            );
        } catch (Exception e) {
            LOGGER.warn("Failed to send batch", e);
            StructuredOpsLogger.warn("event_flush_send_error", Map.of("error", e.toString()));
        }
        return false;
    }

    private void spool(LatticeConfig snapshot, byte[] raw) {
        try {
            Files.createDirectories(snapshot.spoolDir);
            String fileName = System.currentTimeMillis() + "-" + UUID.randomUUID() + ".json.gz";
            Path path = snapshot.spoolDir.resolve(fileName);
            Files.write(path, gzip(raw));
            StructuredOpsLogger.warn("event_spooled", Map.of("path", path.toString(), "bytes", raw.length));
        } catch (IOException e) {
            LOGGER.warn("Failed to spool batch", e);
            StructuredOpsLogger.error("event_spool_failed", Map.of("error", e.toString()));
        }
    }

    private void resendSpool(LatticeConfig snapshot) {
        if (!Files.exists(snapshot.spoolDir)) {
            return;
        }
        try (Stream<Path> stream = Files.list(snapshot.spoolDir)) {
            stream
                .filter(path -> path.getFileName().toString().endsWith(".json.gz"))
                .limit(5)
                .forEach(path -> {
                    try {
                        byte[] data = Files.readAllBytes(path);
                        if (sendGzip(snapshot, data)) {
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

    private boolean sendGzip(LatticeConfig snapshot, byte[] gzipped) {
        try {
            HttpRequest.Builder builder = BackendClient.request(snapshot, INGEST_PATH, Duration.ofSeconds(10))
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
