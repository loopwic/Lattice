package com.lattice.rcon;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.lattice.Lattice;
import com.lattice.config.LatticeConfig;
import com.lattice.http.BackendClient;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;

import java.io.InputStream;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Properties;

public final class RconConfigReporter {
    private static final Logger LOGGER = Lattice.LOGGER;
    private static final Gson GSON = new GsonBuilder().serializeNulls().create();
    private static final String RCON_CONFIG_PATH = "/v2/ops/rcon-config";

    private RconConfigReporter() {
    }

    public static void reportAsync(MinecraftServer server) {
        LatticeConfig config = Lattice.getConfig();
        if (config == null) {
            return;
        }
        Thread thread = new Thread(() -> report(server, config), "lattice-rcon");
        thread.setDaemon(true);
        thread.start();
    }

    public static void report(MinecraftServer server, LatticeConfig config) {
        try {
            Path serverProperties = resolveServerProperties();
            if (serverProperties == null || !Files.exists(serverProperties)) {
                LOGGER.warn("RCON config push skipped: server.properties not found");
                return;
            }
            Properties props = new Properties();
            try (InputStream input = Files.newInputStream(serverProperties)) {
                props.load(input);
            }
            boolean enabled = Boolean.parseBoolean(props.getProperty("enable-rcon", "false"));
            String password = props.getProperty("rcon.password", "").trim();
            String portRaw = props.getProperty("rcon.port", "25575").trim();
            int port = 25575;
            try {
                port = Integer.parseInt(portRaw);
            } catch (NumberFormatException ignored) {
                // keep default
            }
            if (!enabled || password.isEmpty()) {
                return;
            }
            String host = config.rconHost != null && !config.rconHost.trim().isEmpty()
                ? config.rconHost.trim()
                : "127.0.0.1";

            RconPayload payload = new RconPayload(host, port, password, true, "mod");
            String body = GSON.toJson(payload);

            HttpRequest.Builder builder = BackendClient.request(config, RCON_CONFIG_PATH, Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body));
            HttpResponse<String> response = BackendClient.client().send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                LOGGER.warn("RCON config upload failed: {}", response.statusCode());
                return;
            }
            LOGGER.info("RCON config uploaded: {}:{}", host, port);
        } catch (Exception e) {
            LOGGER.warn("RCON config upload failed", e);
        }
    }

    private static Path resolveServerProperties() {
        try {
            Path gameDir = FabricLoader.getInstance().getGameDir();
            if (gameDir != null) {
                return gameDir.resolve("server.properties");
            }
        } catch (Exception ignored) {
            // ignore
        }
        return null;
    }

    private record RconPayload(String host, int port, String password, boolean enabled, String source) {
    }
}
