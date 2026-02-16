package com.lattice.config.sync;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.lattice.Lattice;
import com.lattice.config.LatticeConfig;
import com.lattice.config.RuntimeConfigStore;
import com.lattice.http.BackendClient;
import com.lattice.logging.StructuredOpsLogger;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public final class ConfigSyncManager {
    private static final Gson GSON = new GsonBuilder().serializeNulls().create();
    private static final long LOOP_INTERVAL_MS = 1000L;
    private static final long PULL_INTERVAL_MS = 15_000L;
    private static final long HEARTBEAT_INTERVAL_MS = 15_000L;
    private static final long[] RECONNECT_BACKOFF_MS = new long[]{1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L};

    private final RuntimeConfigStore configStore;
    private final AtomicLong knownRevision = new AtomicLong(0L);

    private ScheduledExecutorService executor;
    private volatile boolean running;
    private volatile WebSocket webSocket;
    private volatile long nextPullAtMs;
    private volatile long nextConnectAtMs;
    private volatile long nextHeartbeatAtMs;
    private volatile int reconnectAttempt;

    public ConfigSyncManager(RuntimeConfigStore configStore) {
        this.configStore = configStore;
    }

    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "lattice-config-sync");
            thread.setDaemon(true);
            return thread;
        });
        long now = System.currentTimeMillis();
        nextPullAtMs = now + 500L;
        nextConnectAtMs = now + 500L;
        nextHeartbeatAtMs = now + HEARTBEAT_INTERVAL_MS;
        executor.scheduleAtFixedRate(this::loop, 200L, LOOP_INTERVAL_MS, TimeUnit.MILLISECONDS);
        StructuredOpsLogger.info(
            "config_sync_started",
            Map.of("server_id", safeServerId(configStore.current()))
        );
    }

    public synchronized void stop() {
        running = false;
        WebSocket socket = webSocket;
        webSocket = null;
        if (socket != null) {
            try {
                socket.sendClose(WebSocket.NORMAL_CLOSURE, "shutdown");
            } catch (Exception ignored) {
                // ignore
            }
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        StructuredOpsLogger.info("config_sync_stopped", Map.of());
    }

    private void loop() {
        if (!running) {
            return;
        }
        long now = System.currentTimeMillis();
        if (webSocket == null && now >= nextConnectAtMs) {
            connectWebSocket(now);
        }
        if (now >= nextPullAtMs) {
            pullLatestConfig();
            nextPullAtMs = now + PULL_INTERVAL_MS;
        }
        if (webSocket != null && now >= nextHeartbeatAtMs) {
            try {
                webSocket.sendText("ping", true);
            } catch (Exception e) {
                onSocketFailure("heartbeat_failed", e);
            } finally {
                nextHeartbeatAtMs = now + HEARTBEAT_INTERVAL_MS;
            }
        }
    }

    private void connectWebSocket(long now) {
        LatticeConfig config = configStore.current();
        String serverId = safeServerId(config);
        String encodedServerId = URLEncoder.encode(serverId, StandardCharsets.UTF_8);
        URI streamUri = toWebSocketUri(resolveUri(config, "/v2/ops/mod-config/stream?server_id=" + encodedServerId));

        HttpClient client = BackendClient.client();
        try {
            WebSocket.Builder builder = client.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10));
            String token = config.apiToken == null ? "" : config.apiToken.trim();
            if (!token.isEmpty()) {
                builder.header("Authorization", "Bearer " + token);
            }
            builder.buildAsync(streamUri, new SyncWebSocketListener()).whenComplete((socket, error) -> {
                if (error != null) {
                    onSocketFailure("connect_failed", error);
                    return;
                }
                webSocket = socket;
                reconnectAttempt = 0;
                nextHeartbeatAtMs = System.currentTimeMillis() + HEARTBEAT_INTERVAL_MS;
                StructuredOpsLogger.info(
                    "config_sync_ws_connected",
                    Map.of("server_id", serverId, "uri", streamUri.toString())
                );
            });
        } catch (Exception e) {
            onSocketFailure("connect_failed", e);
        }
        nextConnectAtMs = now + RECONNECT_BACKOFF_MS[Math.min(reconnectAttempt, RECONNECT_BACKOFF_MS.length - 1)];
    }

    private void pullLatestConfig() {
        LatticeConfig config = configStore.current();
        String serverId = safeServerId(config);
        long afterRevision = knownRevision.get();
        String path = "/v2/ops/mod-config/pull?server_id="
            + URLEncoder.encode(serverId, StandardCharsets.UTF_8)
            + "&after_revision=" + afterRevision;
        try {
            HttpRequest.Builder builder = BackendClient.request(config, path, Duration.ofSeconds(10)).GET();
            HttpResponse<String> response = BackendClient.client().send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                StructuredOpsLogger.warn(
                    "config_sync_pull_failed",
                    Map.of("server_id", serverId, "status", response.statusCode())
                );
                return;
            }
            String body = response.body();
            if (body == null || body.trim().isEmpty() || "null".equals(body.trim())) {
                return;
            }
            JsonElement element = GSON.fromJson(body, JsonElement.class);
            if (element != null && element.isJsonObject()) {
                handleEnvelope(element.getAsJsonObject(), "pull");
            }
        } catch (Exception e) {
            StructuredOpsLogger.warn(
                "config_sync_pull_error",
                Map.of("server_id", serverId, "error", e.toString())
            );
        }
    }

    private void handleEnvelope(JsonObject object, String source) {
        Envelope envelope;
        try {
            envelope = GSON.fromJson(object, Envelope.class);
        } catch (Exception e) {
            StructuredOpsLogger.warn(
                "config_sync_invalid_payload",
                Map.of("source", source, "error", e.toString())
            );
            return;
        }
        if (envelope == null || envelope.server_id == null || envelope.config == null) {
            return;
        }
        if (envelope.revision <= knownRevision.get()) {
            return;
        }

        LatticeConfig oldConfig = configStore.current();
        List<String> changedKeys = List.of();
        try {
            LatticeConfig nextConfig = LatticeConfig.fromJsonObject(envelope.config, oldConfig);
            changedKeys = diffKeys(oldConfig.toJsonObject(), nextConfig.toJsonObject());
            nextConfig.save(LatticeConfig.configPath());
            Lattice.applyConfig(nextConfig, "remote_revision_" + envelope.revision);
            knownRevision.set(envelope.revision);
            sendAck(envelope.server_id, envelope.revision, "APPLIED", null, changedKeys);
            StructuredOpsLogger.info(
                "config_sync_applied",
                Map.of(
                    "server_id", envelope.server_id,
                    "revision", envelope.revision,
                    "source", source,
                    "changed_keys", changedKeys
                )
            );
        } catch (Exception e) {
            sendAck(envelope.server_id, envelope.revision, "REJECTED", e.toString(), changedKeys);
            StructuredOpsLogger.error(
                "config_sync_apply_failed",
                Map.of("server_id", envelope.server_id, "revision", envelope.revision, "error", e.toString())
            );
        }
    }

    private void sendAck(
        String serverId,
        long revision,
        String status,
        String message,
        List<String> changedKeys
    ) {
        LatticeConfig config = configStore.current();
        JsonObject ack = new JsonObject();
        ack.addProperty("server_id", serverId);
        ack.addProperty("revision", revision);
        ack.addProperty("status", status);
        ack.addProperty("applied_at_ms", System.currentTimeMillis());
        if (message != null && !message.isBlank()) {
            ack.addProperty("message", message);
        }
        JsonArrayBuilder arrayBuilder = new JsonArrayBuilder();
        for (String key : changedKeys) {
            arrayBuilder.add(key);
        }
        ack.add("changed_keys", arrayBuilder.build());

        try {
            HttpRequest.Builder builder = BackendClient.request(config, "/v2/ops/mod-config/ack", Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(ack)));
            BackendClient.client().send(builder.build(), HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            StructuredOpsLogger.warn(
                "config_sync_ack_failed",
                Map.of("server_id", serverId, "revision", revision, "error", e.toString())
            );
        }
    }

    private void onSocketFailure(String event, Throwable error) {
        webSocket = null;
        reconnectAttempt = Math.min(reconnectAttempt + 1, RECONNECT_BACKOFF_MS.length - 1);
        nextConnectAtMs = System.currentTimeMillis() + RECONNECT_BACKOFF_MS[reconnectAttempt];
        StructuredOpsLogger.warn(
            "config_sync_ws_" + event,
            Map.of("attempt", reconnectAttempt + 1, "error", error.toString())
        );
    }

    private URI resolveUri(LatticeConfig config, String pathWithQuery) {
        String base = config.backendUrl == null ? "" : config.backendUrl.trim();
        if (base.isEmpty()) {
            throw new IllegalArgumentException("backend_url is empty");
        }
        String path = pathWithQuery == null ? "" : pathWithQuery.trim();
        if (path.isEmpty()) {
            return URI.create(base);
        }
        boolean baseHasSlash = base.endsWith("/");
        boolean pathHasSlash = path.startsWith("/");
        if (baseHasSlash && pathHasSlash) {
            return URI.create(base.substring(0, base.length() - 1) + path);
        }
        if (!baseHasSlash && !pathHasSlash) {
            return URI.create(base + "/" + path);
        }
        return URI.create(base + path);
    }

    private URI toWebSocketUri(URI httpUri) {
        String scheme = httpUri.getScheme();
        String wsScheme = "https".equalsIgnoreCase(scheme) ? "wss" : "ws";
        try {
            return new URI(
                wsScheme,
                httpUri.getUserInfo(),
                httpUri.getHost(),
                httpUri.getPort(),
                httpUri.getPath(),
                httpUri.getQuery(),
                httpUri.getFragment()
            );
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid websocket uri: " + httpUri, e);
        }
    }

    private String safeServerId(LatticeConfig config) {
        if (config == null || config.serverId == null || config.serverId.trim().isEmpty()) {
            return "server-01";
        }
        return config.serverId.trim().toLowerCase();
    }

    private List<String> diffKeys(JsonObject oldValue, JsonObject newValue) {
        Set<String> keys = new HashSet<>();
        keys.addAll(oldValue.keySet());
        keys.addAll(newValue.keySet());
        List<String> changed = new ArrayList<>();
        for (String key : keys) {
            JsonElement oldElement = oldValue.get(key);
            JsonElement newElement = newValue.get(key);
            if (oldElement == null && newElement == null) {
                continue;
            }
            if (oldElement == null || newElement == null || !oldElement.equals(newElement)) {
                changed.add(key);
            }
        }
        changed.sort(String::compareTo);
        return changed;
    }

    private final class SyncWebSocketListener implements WebSocket.Listener {
        private final StringBuilder textBuffer = new StringBuilder();

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            textBuffer.append(data);
            if (last) {
                String payload = textBuffer.toString();
                textBuffer.setLength(0);
                try {
                    JsonElement parsed = GSON.fromJson(payload, JsonElement.class);
                    if (parsed != null && parsed.isJsonObject()) {
                        handleEnvelope(parsed.getAsJsonObject(), "ws");
                    }
                } catch (Exception e) {
                    StructuredOpsLogger.warn("config_sync_ws_parse_failed", Map.of("error", e.toString()));
                }
            }
            return WebSocket.Listener.super.onText(webSocket, data, last);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            onSocketFailure("closed_" + statusCode, new IllegalStateException(reason));
            return WebSocket.Listener.super.onClose(webSocket, statusCode, reason);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            onSocketFailure("error", error);
            WebSocket.Listener.super.onError(webSocket, error);
        }
    }

    private static final class Envelope {
        String server_id;
        long revision;
        JsonObject config;
    }

    private static final class JsonArrayBuilder {
        private final com.google.gson.JsonArray array = new com.google.gson.JsonArray();

        void add(String value) {
            array.add(value);
        }

        com.google.gson.JsonArray build() {
            return array;
        }
    }
}
