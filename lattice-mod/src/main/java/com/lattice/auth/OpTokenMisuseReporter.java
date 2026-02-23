package com.lattice.auth;

import com.google.gson.JsonObject;
import com.lattice.Lattice;
import com.lattice.config.LatticeConfig;
import com.lattice.http.BackendClient;
import net.minecraft.server.level.ServerPlayer;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;

final class OpTokenMisuseReporter {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private OpTokenMisuseReporter() {
    }

    static void reportAsync(ServerPlayer attemptPlayer, String tokenOwnerUuidNoDash, LatticeConfig config) {
        if (attemptPlayer == null || config == null) {
            return;
        }
        try {
            JsonObject payload = new JsonObject();
            payload.addProperty("server_id", normalizeServerId(config.serverId));
            payload.addProperty("attempt_player_uuid", normalizeUuidNoDash(attemptPlayer.getUUID().toString()));
            payload.addProperty("attempt_player_name", attemptPlayer.getGameProfile().getName());
            payload.addProperty("token_owner_uuid", normalizeUuidNoDash(tokenOwnerUuidNoDash));

            HttpRequest request = BackendClient.request(config, "/v2/ops/op-token/misuse-alert", TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8))
                .build();

            BackendClient.client()
                .sendAsync(request, HttpResponse.BodyHandlers.discarding())
                .whenComplete((response, error) -> {
                    if (error != null) {
                        Lattice.LOGGER.warn("Report OP token misuse failed", error);
                        return;
                    }
                    if (response == null || response.statusCode() / 100 != 2) {
                        int code = response == null ? -1 : response.statusCode();
                        Lattice.LOGGER.warn("Report OP token misuse rejected: status={}", code);
                    }
                });
        } catch (Exception e) {
            Lattice.LOGGER.warn("Build OP token misuse alert request failed", e);
        }
    }

    private static String normalizeServerId(String raw) {
        if (raw == null) {
            return "server-01";
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        return value.isEmpty() ? "server-01" : value;
    }

    private static String normalizeUuidNoDash(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.trim().replace("-", "").toLowerCase(Locale.ROOT);
    }
}
