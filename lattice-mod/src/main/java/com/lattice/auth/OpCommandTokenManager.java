package com.lattice.auth;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lattice.Lattice;
import com.lattice.config.LatticeConfig;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;

public final class OpCommandTokenManager {
    private static final String HMAC_ALGO = "HmacSHA256";
    private static final String TOKEN_PREFIX = "lattice";
    private static final String TOKEN_VERSION = "v1";
    private static final DateTimeFormatter TOKEN_DAY_FORMATTER = DateTimeFormatter.BASIC_ISO_DATE;

    private final Path statePath;
    private final ZoneId zoneId;
    private final Map<UUID, Long> grants = new HashMap<>();

    public OpCommandTokenManager(Path configDir) {
        this(configDir.resolve("lattice").resolve("op-token-state.json"), ZoneId.systemDefault());
    }

    OpCommandTokenManager(Path statePath, ZoneId zoneId) {
        this.statePath = statePath;
        this.zoneId = zoneId == null ? ZoneId.systemDefault() : zoneId;
        loadState();
    }

    public synchronized AccessDecision checkAccess(CommandSourceStack source, LatticeConfig config) {
        if (config == null || !config.opCommandTokenRequired) {
            return AccessDecision.allow("token check disabled");
        }
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            return AccessDecision.allow("non-player source");
        }

        pruneExpiredLocked();
        Long expiry = grants.get(player.getUUID());
        if (expiry != null && expiry > nowEpochMs()) {
            return AccessDecision.allow("token active");
        }
        return AccessDecision.deny("OP command token required, run /lattice token apply <token>");
    }

    public synchronized TokenApplyResult applyToken(
        ServerPlayer player,
        String rawToken,
        LatticeConfig config
    ) {
        if (player == null) {
            return TokenApplyResult.fail("player is required");
        }
        if (config == null || !config.opCommandTokenRequired) {
            return TokenApplyResult.fail("token check is disabled");
        }

        String secret = config.opCommandTokenSecret == null ? "" : config.opCommandTokenSecret.trim();
        if (secret.isEmpty()) {
            return TokenApplyResult.fail("op_command_token_secret is empty");
        }

        ParsedToken parsed = parseToken(rawToken);
        if (parsed == null) {
            return TokenApplyResult.fail("token format invalid");
        }
        if (!TOKEN_VERSION.equals(parsed.version)) {
            return TokenApplyResult.fail("token version unsupported");
        }

        String today = LocalDate.now(zoneId).format(TOKEN_DAY_FORMATTER);
        if (!today.equals(parsed.day)) {
            return TokenApplyResult.fail("token day is not today");
        }

        String expectedUuid = normalizeUuidNoDash(player.getUUID());
        if (!expectedUuid.equals(parsed.playerUuidNoDash)) {
            return TokenApplyResult.fail("token does not belong to current player");
        }

        String payload = String.join("|", TOKEN_PREFIX, parsed.version, parsed.day, parsed.playerUuidNoDash);
        String expectedSignature;
        try {
            expectedSignature = hmacHex(secret, payload);
        } catch (Exception e) {
            Lattice.LOGGER.warn("Compute token signature failed", e);
            return TokenApplyResult.fail("token verifier internal error");
        }
        if (!secureEqualsHex(expectedSignature, parsed.signatureHex)) {
            return TokenApplyResult.fail("token signature mismatch");
        }

        long expiresAt = LocalDate.now(zoneId)
            .plusDays(1)
            .atStartOfDay(zoneId)
            .toInstant()
            .toEpochMilli();
        grants.put(player.getUUID(), expiresAt);
        pruneExpiredLocked();
        persistStateLocked();
        return TokenApplyResult.ok("token accepted, expires at " + Instant.ofEpochMilli(expiresAt).atZone(zoneId));
    }

    public synchronized OptionalLong getGrantExpiry(ServerPlayer player) {
        if (player == null) {
            return OptionalLong.empty();
        }
        pruneExpiredLocked();
        Long value = grants.get(player.getUUID());
        if (value == null || value <= nowEpochMs()) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(value);
    }

    private static ParsedToken parseToken(String raw) {
        if (raw == null) {
            return null;
        }
        String token = raw.trim();
        if (token.isEmpty()) {
            return null;
        }
        String[] parts = token.split("\\.");
        if (parts.length != 5) {
            return null;
        }
        if (!TOKEN_PREFIX.equals(parts[0])) {
            return null;
        }

        String version = parts[1].trim().toLowerCase(Locale.ROOT);
        String day = parts[2].trim();
        String uuidNoDash = parts[3].trim().toLowerCase(Locale.ROOT);
        String signature = parts[4].trim().toLowerCase(Locale.ROOT);

        if (!day.matches("\\d{8}")) {
            return null;
        }
        if (!uuidNoDash.matches("[0-9a-f]{32}")) {
            return null;
        }
        if (!signature.matches("[0-9a-f]{64}")) {
            return null;
        }

        return new ParsedToken(version, day, uuidNoDash, signature);
    }

    private static String normalizeUuidNoDash(UUID uuid) {
        return uuid.toString().replace("-", "").toLowerCase(Locale.ROOT);
    }

    private static boolean secureEqualsHex(String left, String right) {
        try {
            byte[] leftBytes = HexFormat.of().parseHex(left);
            byte[] rightBytes = HexFormat.of().parseHex(right);
            return MessageDigest.isEqual(leftBytes, rightBytes);
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static String hmacHex(String secret, String payload) throws Exception {
        Mac mac = Mac.getInstance(HMAC_ALGO);
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGO));
        return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    }

    private long nowEpochMs() {
        return System.currentTimeMillis();
    }

    private void loadState() {
        synchronized (this) {
            grants.clear();
            if (!Files.exists(statePath)) {
                return;
            }
            try {
                String content = Files.readString(statePath, StandardCharsets.UTF_8);
                JsonElement rootElement = JsonParser.parseString(content);
                if (!rootElement.isJsonObject()) {
                    return;
                }
                JsonObject root = rootElement.getAsJsonObject();
                JsonObject grantObject = root.getAsJsonObject("grants");
                if (grantObject == null) {
                    return;
                }
                for (Map.Entry<String, JsonElement> entry : grantObject.entrySet()) {
                    try {
                        UUID uuid = UUID.fromString(entry.getKey());
                        long expiry = entry.getValue().getAsLong();
                        if (expiry > 0L) {
                            grants.put(uuid, expiry);
                        }
                    } catch (Exception ignored) {
                        // skip malformed entry
                    }
                }
                pruneExpiredLocked();
            } catch (Exception e) {
                Lattice.LOGGER.warn("Load op token state failed: {}", statePath, e);
            }
        }
    }

    private void persistStateLocked() {
        try {
            Files.createDirectories(statePath.getParent());
            JsonObject root = new JsonObject();
            root.addProperty("version", 1);
            JsonObject grantObject = new JsonObject();
            for (Map.Entry<UUID, Long> entry : grants.entrySet()) {
                grantObject.addProperty(entry.getKey().toString(), entry.getValue());
            }
            root.add("grants", grantObject);
            String json = root.toString();

            Path tmp = statePath.resolveSibling(statePath.getFileName().toString() + ".tmp");
            Files.writeString(
                tmp,
                json,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
            );
            try {
                Files.move(tmp, statePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(tmp, statePath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            Lattice.LOGGER.warn("Persist op token state failed: {}", statePath, e);
        }
    }

    private void pruneExpiredLocked() {
        long now = nowEpochMs();
        boolean changed = grants.entrySet().removeIf(entry -> entry.getValue() <= now);
        if (changed) {
            persistStateLocked();
        }
    }

    public record AccessDecision(boolean allowed, String reason) {
        public static AccessDecision allow(String reason) {
            return new AccessDecision(true, reason);
        }

        public static AccessDecision deny(String reason) {
            return new AccessDecision(false, reason);
        }
    }

    public record TokenApplyResult(boolean success, String message) {
        public static TokenApplyResult ok(String message) {
            return new TokenApplyResult(true, message);
        }

        public static TokenApplyResult fail(String message) {
            return new TokenApplyResult(false, message);
        }
    }

    private record ParsedToken(
        String version,
        String day,
        String playerUuidNoDash,
        String signatureHex
    ) {
    }
}
