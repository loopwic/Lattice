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
    private static final String TOKEN_VERSION = "v2";
    private static final DateTimeFormatter TOKEN_DAY_FORMATTER = DateTimeFormatter.BASIC_ISO_DATE;

    private final Path statePath;
    private final ZoneId zoneId;
    private final Map<UUID, PlayerGrant> playerGrants = new HashMap<>();
    private final Map<String, TokenBinding> tokenBindings = new HashMap<>();
    private final Map<String, Long> revokedTokenIds = new HashMap<>();

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
        PlayerGrant grant = playerGrants.get(player.getUUID());
        if (grant != null && grant.expiresAt > nowEpochMs()) {
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

        String payload = String.join("|", TOKEN_PREFIX, parsed.version, parsed.day, parsed.tokenId);
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

        pruneExpiredLocked();
        long now = nowEpochMs();
        long expiresAt = LocalDate.now(zoneId)
            .plusDays(1)
            .atStartOfDay(zoneId)
            .toInstant()
            .toEpochMilli();

        Long revokedUntil = revokedTokenIds.get(parsed.tokenId);
        if (revokedUntil != null && revokedUntil > now) {
            return TokenApplyResult.fail("token revoked due to multi-account misuse");
        }

        UUID playerUuid = player.getUUID();
        TokenBinding binding = tokenBindings.get(parsed.tokenId);
        if (binding != null && binding.expiresAt > now && !binding.ownerUuid.equals(playerUuid)) {
            invalidateTokenLocked(parsed.tokenId, binding.ownerUuid, expiresAt);
            OpTokenMisuseReporter.reportAsync(player, normalizeUuidNoDash(binding.ownerUuid), config);
            return TokenApplyResult.fail("token misuse detected, token revoked and warning sent");
        }

        playerGrants.put(playerUuid, new PlayerGrant(parsed.tokenId, expiresAt));
        tokenBindings.put(parsed.tokenId, new TokenBinding(playerUuid, expiresAt));
        persistStateLocked();
        return TokenApplyResult.ok("token accepted, expires at " + Instant.ofEpochMilli(expiresAt).atZone(zoneId));
    }

    public synchronized OptionalLong getGrantExpiry(ServerPlayer player) {
        if (player == null) {
            return OptionalLong.empty();
        }
        pruneExpiredLocked();
        PlayerGrant grant = playerGrants.get(player.getUUID());
        if (grant == null || grant.expiresAt <= nowEpochMs()) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(grant.expiresAt);
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
        String tokenId = parts[3].trim().toLowerCase(Locale.ROOT);
        String signature = parts[4].trim().toLowerCase(Locale.ROOT);

        if (!day.matches("\\d{8}")) {
            return null;
        }
        if (!tokenId.matches("[0-9a-f]{32}")) {
            return null;
        }
        if (!signature.matches("[0-9a-f]{64}")) {
            return null;
        }

        return new ParsedToken(version, day, tokenId, signature);
    }

    private static String normalizeUuidNoDash(UUID uuid) {
        if (uuid == null) {
            return "";
        }
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
            playerGrants.clear();
            tokenBindings.clear();
            revokedTokenIds.clear();
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

                JsonObject grantObject = root.getAsJsonObject("player_grants");
                if (grantObject != null) {
                    for (Map.Entry<String, JsonElement> entry : grantObject.entrySet()) {
                        UUID playerUuid;
                        try {
                            playerUuid = UUID.fromString(entry.getKey());
                        } catch (IllegalArgumentException ignored) {
                            continue;
                        }
                        if (!entry.getValue().isJsonObject()) {
                            continue;
                        }
                        JsonObject value = entry.getValue().getAsJsonObject();
                        String tokenId = readString(value, "token_id");
                        Long expiresAt = readLong(value, "expires_at");
                        if (tokenId == null || expiresAt == null) {
                            continue;
                        }
                        if (!tokenId.matches("[0-9a-f]{32}") || expiresAt <= 0L) {
                            continue;
                        }
                        playerGrants.put(playerUuid, new PlayerGrant(tokenId, expiresAt));
                    }
                }

                JsonObject bindingObject = root.getAsJsonObject("token_bindings");
                if (bindingObject != null) {
                    for (Map.Entry<String, JsonElement> entry : bindingObject.entrySet()) {
                        String tokenId = entry.getKey().trim().toLowerCase(Locale.ROOT);
                        if (!tokenId.matches("[0-9a-f]{32}")) {
                            continue;
                        }
                        if (!entry.getValue().isJsonObject()) {
                            continue;
                        }
                        JsonObject value = entry.getValue().getAsJsonObject();
                        String ownerRaw = readString(value, "owner_uuid");
                        Long expiresAt = readLong(value, "expires_at");
                        if (ownerRaw == null || expiresAt == null || expiresAt <= 0L) {
                            continue;
                        }
                        UUID owner;
                        try {
                            owner = UUID.fromString(ownerRaw);
                        } catch (IllegalArgumentException ignored) {
                            continue;
                        }
                        tokenBindings.put(tokenId, new TokenBinding(owner, expiresAt));
                    }
                }

                JsonObject revokedObject = root.getAsJsonObject("revoked_tokens");
                if (revokedObject != null) {
                    for (Map.Entry<String, JsonElement> entry : revokedObject.entrySet()) {
                        String tokenId = entry.getKey().trim().toLowerCase(Locale.ROOT);
                        if (!tokenId.matches("[0-9a-f]{32}")) {
                            continue;
                        }
                        try {
                            long until = entry.getValue().getAsLong();
                            if (until > 0L) {
                                revokedTokenIds.put(tokenId, until);
                            }
                        } catch (Exception ignored) {
                            // skip malformed entry
                        }
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
            root.addProperty("version", 2);

            JsonObject grantObject = new JsonObject();
            for (Map.Entry<UUID, PlayerGrant> entry : playerGrants.entrySet()) {
                JsonObject value = new JsonObject();
                value.addProperty("token_id", entry.getValue().tokenId);
                value.addProperty("expires_at", entry.getValue().expiresAt);
                grantObject.add(entry.getKey().toString(), value);
            }
            root.add("player_grants", grantObject);

            JsonObject bindingObject = new JsonObject();
            for (Map.Entry<String, TokenBinding> entry : tokenBindings.entrySet()) {
                JsonObject value = new JsonObject();
                value.addProperty("owner_uuid", entry.getValue().ownerUuid.toString());
                value.addProperty("expires_at", entry.getValue().expiresAt);
                bindingObject.add(entry.getKey(), value);
            }
            root.add("token_bindings", bindingObject);

            JsonObject revokedObject = new JsonObject();
            for (Map.Entry<String, Long> entry : revokedTokenIds.entrySet()) {
                revokedObject.addProperty(entry.getKey(), entry.getValue());
            }
            root.add("revoked_tokens", revokedObject);

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
        boolean changed = false;

        changed |= playerGrants.entrySet().removeIf(entry -> entry.getValue().expiresAt <= now);
        changed |= tokenBindings.entrySet().removeIf(entry -> entry.getValue().expiresAt <= now);
        changed |= revokedTokenIds.entrySet().removeIf(entry -> entry.getValue() <= now);

        changed |= playerGrants.entrySet().removeIf(entry -> {
            PlayerGrant grant = entry.getValue();
            TokenBinding binding = tokenBindings.get(grant.tokenId);
            return binding == null || !entry.getKey().equals(binding.ownerUuid) || binding.expiresAt <= now;
        });

        if (changed) {
            persistStateLocked();
        }
    }

    private void invalidateTokenLocked(String tokenId, UUID ownerUuid, long revokedUntil) {
        if (tokenId == null || tokenId.isBlank()) {
            return;
        }
        tokenBindings.remove(tokenId);
        revokedTokenIds.put(tokenId, revokedUntil);
        if (ownerUuid != null) {
            PlayerGrant grant = playerGrants.get(ownerUuid);
            if (grant != null && tokenId.equals(grant.tokenId)) {
                playerGrants.remove(ownerUuid);
            }
        }
        persistStateLocked();
    }

    private static String readString(JsonObject obj, String field) {
        if (obj == null || field == null) {
            return null;
        }
        JsonElement value = obj.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            return null;
        }
        String text = value.getAsString();
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static Long readLong(JsonObject obj, String field) {
        if (obj == null || field == null) {
            return null;
        }
        JsonElement value = obj.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            return null;
        }
        try {
            return value.getAsLong();
        } catch (Exception ignored) {
            return null;
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
        String tokenId,
        String signatureHex
    ) {
    }

    private record PlayerGrant(String tokenId, long expiresAt) {
    }

    private record TokenBinding(UUID ownerUuid, long expiresAt) {
    }
}
