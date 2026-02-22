package com.lattice.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.lattice.Lattice;
import org.slf4j.Logger;

import net.fabricmc.loader.api.FabricLoader;

import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlParseResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class LatticeConfig {
    private static final Logger LOGGER = Lattice.LOGGER;

    public final String backendUrl;
    public final String apiToken;
    public final String serverId;
    public final String rconHost;
    public final int batchSize;
    public final long flushIntervalMs;
    public final Path spoolDir;
    public final long contextWindowMs;
    public final boolean auditEnabled;
    public final long auditIntervalMinutes;
    public final int auditPlayersPerTick;
    public final boolean scanEnabled;
    public final long scanIntervalMinutes;
    public final int scanContainersPerTick;
    public final int scanRs2NetworksPerTick;
    public final long scanRescanCooldownMinutes;
    public final boolean scanIncludeContainers;
    public final boolean scanIncludeRs2;
    public final long scanMaxAvgTickMs;
    public final int scanMaxOnlinePlayers;
    public final boolean scanWorldOfflineEnabled;
    public final boolean scanSbOfflineEnabled;
    public final boolean scanRs2OfflineEnabled;
    public final int scanOfflineChunksPerTick;
    public final int scanOfflineSourcesPerTick;
    public final int scanOfflineWorkers;
    public final long scanOfflineChunkIntervalMs;
    public final boolean scanIncludeOnlineRuntime;
    public final boolean registryUploadEnabled;
    public final int registryUploadChunkSize;
    public final java.util.List<String> registryUploadLanguages;
    public final java.util.Set<String> scanItemFilter;
    public final boolean opCommandTokenRequired;
    public final String opCommandTokenSecret;
    public final String opsLogLevel;

    private LatticeConfig(
        String backendUrl,
        String apiToken,
        String serverId,
        String rconHost,
        int batchSize,
        long flushIntervalMs,
        Path spoolDir,
        long contextWindowMs,
        boolean auditEnabled,
        long auditIntervalMinutes,
        int auditPlayersPerTick,
        boolean scanEnabled,
        long scanIntervalMinutes,
        int scanContainersPerTick,
        int scanRs2NetworksPerTick,
        long scanRescanCooldownMinutes,
        boolean scanIncludeContainers,
        boolean scanIncludeRs2,
        long scanMaxAvgTickMs,
        int scanMaxOnlinePlayers,
        boolean scanWorldOfflineEnabled,
        boolean scanSbOfflineEnabled,
        boolean scanRs2OfflineEnabled,
        int scanOfflineChunksPerTick,
        int scanOfflineSourcesPerTick,
        int scanOfflineWorkers,
        long scanOfflineChunkIntervalMs,
        boolean scanIncludeOnlineRuntime,
        boolean registryUploadEnabled,
        int registryUploadChunkSize,
        java.util.List<String> registryUploadLanguages,
        java.util.Set<String> scanItemFilter,
        boolean opCommandTokenRequired,
        String opCommandTokenSecret,
        String opsLogLevel
    ) {
        this.backendUrl = backendUrl;
        this.apiToken = apiToken;
        this.serverId = serverId;
        this.rconHost = rconHost;
        this.batchSize = batchSize;
        this.flushIntervalMs = flushIntervalMs;
        this.spoolDir = spoolDir;
        this.contextWindowMs = contextWindowMs;
        this.auditEnabled = auditEnabled;
        this.auditIntervalMinutes = auditIntervalMinutes;
        this.auditPlayersPerTick = auditPlayersPerTick;
        this.scanEnabled = scanEnabled;
        this.scanIntervalMinutes = scanIntervalMinutes;
        this.scanContainersPerTick = scanContainersPerTick;
        this.scanRs2NetworksPerTick = scanRs2NetworksPerTick;
        this.scanRescanCooldownMinutes = scanRescanCooldownMinutes;
        this.scanIncludeContainers = scanIncludeContainers;
        this.scanIncludeRs2 = scanIncludeRs2;
        this.scanMaxAvgTickMs = scanMaxAvgTickMs;
        this.scanMaxOnlinePlayers = scanMaxOnlinePlayers;
        this.scanWorldOfflineEnabled = scanWorldOfflineEnabled;
        this.scanSbOfflineEnabled = scanSbOfflineEnabled;
        this.scanRs2OfflineEnabled = scanRs2OfflineEnabled;
        this.scanOfflineChunksPerTick = scanOfflineChunksPerTick;
        this.scanOfflineSourcesPerTick = scanOfflineSourcesPerTick;
        this.scanOfflineWorkers = scanOfflineWorkers;
        this.scanOfflineChunkIntervalMs = scanOfflineChunkIntervalMs;
        this.scanIncludeOnlineRuntime = scanIncludeOnlineRuntime;
        this.registryUploadEnabled = registryUploadEnabled;
        this.registryUploadChunkSize = registryUploadChunkSize;
        this.registryUploadLanguages = registryUploadLanguages;
        this.scanItemFilter = scanItemFilter;
        this.opCommandTokenRequired = opCommandTokenRequired;
        this.opCommandTokenSecret = opCommandTokenSecret == null ? "" : opCommandTokenSecret.trim();
        this.opsLogLevel = normalizeLogLevel(opsLogLevel);
    }

    public static Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("lattice.toml");
    }

    public static LatticeConfig load() {
        return load(configPath());
    }

    public static LatticeConfig load(Path path) {
        Path configDir = path.getParent();
        if (configDir == null) {
            configDir = FabricLoader.getInstance().getConfigDir();
        }
        LatticeConfig defaults = defaultConfig(configDir);

        if (!Files.exists(path)) {
            try {
                Files.createDirectories(configDir);
                Files.writeString(path, defaults.toTomlString(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                LOGGER.warn("Failed to write default config", e);
            }
            return defaults;
        }

        try {
            TomlParseResult result = Toml.parse(path);
            if (result.hasErrors()) {
                result.errors().forEach(error -> LOGGER.warn("Config parse error: {}", error));
                return defaults;
            }
            return fromToml(result, configDir, defaults);
        } catch (Exception e) {
            LOGGER.warn("Failed to load config, using defaults", e);
            return defaults;
        }
    }

    public static LatticeConfig fromJsonObject(JsonObject json, LatticeConfig defaults) {
        if (json == null) {
            return defaults;
        }
        Path configDir = FabricLoader.getInstance().getConfigDir();

        String backendUrl = getString(json, "backend_url", defaults.backendUrl);
        String apiToken = getString(json, "api_token", defaults.apiToken);
        String serverId = getString(json, "server_id", defaults.serverId);
        String rconHost = getString(json, "rcon_host", defaults.rconHost);
        int batchSize = getInt(json, "batch_size", defaults.batchSize);
        long flushIntervalMs = getLong(json, "flush_interval_ms", defaults.flushIntervalMs);
        String spoolDir = getString(json, "spool_dir", defaults.spoolDir.getFileName().toString());
        long contextWindowMs = getLong(json, "context_window_ms", defaults.contextWindowMs);
        boolean auditEnabled = getBoolean(json, "audit_enabled", defaults.auditEnabled);
        long auditIntervalMinutes = getLong(json, "audit_interval_minutes", defaults.auditIntervalMinutes);
        int auditPlayersPerTick = getInt(json, "audit_players_per_tick", defaults.auditPlayersPerTick);

        boolean scanEnabled = getBoolean(json, "scan_enabled", defaults.scanEnabled);
        long scanIntervalMinutes = getLong(json, "scan_interval_minutes", defaults.scanIntervalMinutes);
        int scanContainersPerTick = getInt(json, "scan_containers_per_tick", defaults.scanContainersPerTick);
        int scanRs2NetworksPerTick = getInt(json, "scan_rs2_networks_per_tick", defaults.scanRs2NetworksPerTick);
        long scanRescanCooldownMinutes = getLong(json, "scan_rescan_cooldown_minutes", defaults.scanRescanCooldownMinutes);
        boolean scanIncludeContainers = getBoolean(json, "scan_include_containers", defaults.scanIncludeContainers);
        boolean scanIncludeRs2 = getBoolean(json, "scan_include_rs2", defaults.scanIncludeRs2);
        long scanMaxAvgTickMs = getLong(json, "scan_max_avg_tick_ms", defaults.scanMaxAvgTickMs);
        int scanMaxOnlinePlayers = getInt(json, "scan_max_online_players", defaults.scanMaxOnlinePlayers);

        boolean scanWorldOfflineEnabled = getBoolean(json, "scan_world_offline_enabled", defaults.scanWorldOfflineEnabled);
        boolean scanSbOfflineEnabled = getBoolean(json, "scan_sb_offline_enabled", defaults.scanSbOfflineEnabled);
        boolean scanRs2OfflineEnabled = getBoolean(json, "scan_rs2_offline_enabled", defaults.scanRs2OfflineEnabled);
        int scanOfflineChunksPerTick = getInt(json, "scan_offline_chunks_per_tick", defaults.scanOfflineChunksPerTick);
        int scanOfflineSourcesPerTick = getInt(json, "scan_offline_sources_per_tick", defaults.scanOfflineSourcesPerTick);
        int scanOfflineWorkers = getInt(json, "scan_offline_workers", defaults.scanOfflineWorkers);
        long scanOfflineChunkIntervalMs = getLong(json, "scan_offline_chunk_interval_ms", defaults.scanOfflineChunkIntervalMs);
        boolean scanIncludeOnlineRuntime = getBoolean(json, "scan_include_online_runtime", defaults.scanIncludeOnlineRuntime);

        boolean registryUploadEnabled = getBoolean(json, "registry_upload_enabled", defaults.registryUploadEnabled);
        int registryUploadChunkSize = getInt(json, "registry_upload_chunk_size", defaults.registryUploadChunkSize);
        java.util.List<String> registryUploadLanguages = getStringList(json, "registry_upload_languages", defaults.registryUploadLanguages);
        java.util.Set<String> scanItemFilter = getStringSet(json, "scan_item_filter", defaults.scanItemFilter);
        boolean opCommandTokenRequired = getBoolean(json, "op_command_token_required", defaults.opCommandTokenRequired);
        String opCommandTokenSecret = getString(json, "op_command_token_secret", defaults.opCommandTokenSecret);
        String opsLogLevel = getString(json, "ops_log_level", defaults.opsLogLevel);

        Path spoolPath = spoolDir.trim().isEmpty() ? defaults.spoolDir : configDir.resolve(spoolDir);

        return new LatticeConfig(
            backendUrl,
            apiToken,
            serverId,
            rconHost,
            batchSize,
            flushIntervalMs,
            spoolPath,
            contextWindowMs,
            auditEnabled,
            auditIntervalMinutes,
            auditPlayersPerTick,
            scanEnabled,
            scanIntervalMinutes,
            scanContainersPerTick,
            scanRs2NetworksPerTick,
            scanRescanCooldownMinutes,
            scanIncludeContainers,
            scanIncludeRs2,
            scanMaxAvgTickMs,
            scanMaxOnlinePlayers,
            scanWorldOfflineEnabled,
            scanSbOfflineEnabled,
            scanRs2OfflineEnabled,
            scanOfflineChunksPerTick,
            scanOfflineSourcesPerTick,
            scanOfflineWorkers,
            scanOfflineChunkIntervalMs,
            scanIncludeOnlineRuntime,
            registryUploadEnabled,
            registryUploadChunkSize,
            registryUploadLanguages,
            scanItemFilter,
            opCommandTokenRequired,
            opCommandTokenSecret,
            opsLogLevel
        );
    }

    public void save(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, toTomlString(), StandardCharsets.UTF_8);
    }

    public JsonObject toJsonObject() {
        JsonObject obj = new JsonObject();
        obj.addProperty("backend_url", backendUrl);
        obj.addProperty("api_token", apiToken);
        obj.addProperty("server_id", serverId);
        obj.addProperty("rcon_host", rconHost);
        obj.addProperty("batch_size", batchSize);
        obj.addProperty("flush_interval_ms", flushIntervalMs);
        obj.addProperty("spool_dir", spoolDir.getFileName().toString());
        obj.addProperty("context_window_ms", contextWindowMs);
        obj.addProperty("audit_enabled", auditEnabled);
        obj.addProperty("audit_interval_minutes", auditIntervalMinutes);
        obj.addProperty("audit_players_per_tick", auditPlayersPerTick);
        obj.addProperty("scan_enabled", scanEnabled);
        obj.addProperty("scan_interval_minutes", scanIntervalMinutes);
        obj.addProperty("scan_containers_per_tick", scanContainersPerTick);
        obj.addProperty("scan_rs2_networks_per_tick", scanRs2NetworksPerTick);
        obj.addProperty("scan_rescan_cooldown_minutes", scanRescanCooldownMinutes);
        obj.addProperty("scan_include_containers", scanIncludeContainers);
        obj.addProperty("scan_include_rs2", scanIncludeRs2);
        obj.addProperty("scan_max_avg_tick_ms", scanMaxAvgTickMs);
        obj.addProperty("scan_max_online_players", scanMaxOnlinePlayers);
        obj.addProperty("scan_world_offline_enabled", scanWorldOfflineEnabled);
        obj.addProperty("scan_sb_offline_enabled", scanSbOfflineEnabled);
        obj.addProperty("scan_rs2_offline_enabled", scanRs2OfflineEnabled);
        obj.addProperty("scan_offline_chunks_per_tick", scanOfflineChunksPerTick);
        obj.addProperty("scan_offline_sources_per_tick", scanOfflineSourcesPerTick);
        obj.addProperty("scan_offline_workers", scanOfflineWorkers);
        obj.addProperty("scan_offline_chunk_interval_ms", scanOfflineChunkIntervalMs);
        obj.addProperty("scan_include_online_runtime", scanIncludeOnlineRuntime);
        obj.addProperty("registry_upload_enabled", registryUploadEnabled);
        obj.addProperty("registry_upload_chunk_size", registryUploadChunkSize);
        obj.addProperty("op_command_token_required", opCommandTokenRequired);
        obj.addProperty("op_command_token_secret", opCommandTokenSecret);
        obj.addProperty("ops_log_level", opsLogLevel);

        JsonArray langs = new JsonArray();
        for (String value : registryUploadLanguages) {
            langs.add(value);
        }
        obj.add("registry_upload_languages", langs);

        JsonArray filter = new JsonArray();
        for (String value : scanItemFilter) {
            filter.add(value);
        }
        obj.add("scan_item_filter", filter);

        return obj;
    }

    public String toTomlString() {
        String filter = scanItemFilter.isEmpty()
            ? ""
            : scanItemFilter.stream().sorted().map(value -> "\"" + value + "\"").reduce((a, b) -> a + ", " + b).orElse("");
        String languages = registryUploadLanguages.isEmpty()
            ? ""
            : registryUploadLanguages.stream()
                .map(value -> "\"" + value + "\"")
                .reduce((a, b) -> a + ", " + b)
                .orElse("");
        return String.join("\n",
            "backend_url = \"" + backendUrl + "\"",
            "api_token = \"" + apiToken + "\"",
            "server_id = \"" + serverId + "\"",
            "rcon_host = \"" + rconHost + "\"",
            "batch_size = " + batchSize,
            "flush_interval_ms = " + flushIntervalMs,
            "spool_dir = \"" + spoolDir.getFileName().toString() + "\"",
            "context_window_ms = " + contextWindowMs,
            "audit_enabled = " + auditEnabled,
            "audit_interval_minutes = " + auditIntervalMinutes,
            "audit_players_per_tick = " + auditPlayersPerTick,
            "scan_enabled = " + scanEnabled,
            "scan_interval_minutes = " + scanIntervalMinutes,
            "scan_containers_per_tick = " + scanContainersPerTick,
            "scan_rs2_networks_per_tick = " + scanRs2NetworksPerTick,
            "scan_rescan_cooldown_minutes = " + scanRescanCooldownMinutes,
            "scan_include_containers = " + scanIncludeContainers,
            "scan_include_rs2 = " + scanIncludeRs2,
            "scan_max_avg_tick_ms = " + scanMaxAvgTickMs,
            "scan_max_online_players = " + scanMaxOnlinePlayers,
            "scan_world_offline_enabled = " + scanWorldOfflineEnabled,
            "scan_sb_offline_enabled = " + scanSbOfflineEnabled,
            "scan_rs2_offline_enabled = " + scanRs2OfflineEnabled,
            "scan_offline_chunks_per_tick = " + scanOfflineChunksPerTick,
            "scan_offline_sources_per_tick = " + scanOfflineSourcesPerTick,
            "scan_offline_workers = " + scanOfflineWorkers,
            "scan_offline_chunk_interval_ms = " + scanOfflineChunkIntervalMs,
            "scan_include_online_runtime = " + scanIncludeOnlineRuntime,
            "registry_upload_enabled = " + registryUploadEnabled,
            "registry_upload_chunk_size = " + registryUploadChunkSize,
            "registry_upload_languages = [" + languages + "]",
            "scan_item_filter = [" + filter + "]",
            "op_command_token_required = " + opCommandTokenRequired,
            "op_command_token_secret = \"" + opCommandTokenSecret + "\"",
            "ops_log_level = \"" + opsLogLevel + "\""
        ) + "\n";
    }

    private static LatticeConfig fromToml(TomlParseResult result, Path configDir, LatticeConfig defaults) {
        String backendUrl = result.getString("backend_url");
        String apiToken = result.getString("api_token");
        String serverId = result.getString("server_id");
        String rconHost = result.getString("rcon_host");
        Long batchSize = result.getLong("batch_size");
        Long flushIntervalMs = result.getLong("flush_interval_ms");
        String spoolDir = result.getString("spool_dir");
        Long contextWindowMs = result.getLong("context_window_ms");
        Boolean auditEnabled = result.getBoolean("audit_enabled");
        Long auditIntervalMinutes = result.getLong("audit_interval_minutes");
        Long auditPlayersPerTick = result.getLong("audit_players_per_tick");
        Boolean scanEnabled = result.getBoolean("scan_enabled");
        Long scanIntervalMinutes = result.getLong("scan_interval_minutes");
        Long scanContainersPerTick = result.getLong("scan_containers_per_tick");
        Long scanRs2NetworksPerTick = result.getLong("scan_rs2_networks_per_tick");
        Long scanRescanCooldownMinutes = result.getLong("scan_rescan_cooldown_minutes");
        Boolean scanIncludeContainers = result.getBoolean("scan_include_containers");
        Boolean scanIncludeRs2 = result.getBoolean("scan_include_rs2");
        Long scanMaxAvgTickMs = result.getLong("scan_max_avg_tick_ms");
        Long scanMaxOnlinePlayers = result.getLong("scan_max_online_players");
        Boolean scanWorldOfflineEnabled = result.getBoolean("scan_world_offline_enabled");
        Boolean scanSbOfflineEnabled = result.getBoolean("scan_sb_offline_enabled");
        Boolean scanRs2OfflineEnabled = result.getBoolean("scan_rs2_offline_enabled");
        Long scanOfflineChunksPerTick = result.getLong("scan_offline_chunks_per_tick");
        Long scanOfflineSourcesPerTick = result.getLong("scan_offline_sources_per_tick");
        Long scanOfflineWorkers = result.getLong("scan_offline_workers");
        Long scanOfflineChunkIntervalMs = result.getLong("scan_offline_chunk_interval_ms");
        Boolean scanIncludeOnlineRuntime = result.getBoolean("scan_include_online_runtime");
        Boolean registryUploadEnabled = result.getBoolean("registry_upload_enabled");
        Long registryUploadChunkSize = result.getLong("registry_upload_chunk_size");
        Boolean opCommandTokenRequired = result.getBoolean("op_command_token_required");
        String opCommandTokenSecret = result.getString("op_command_token_secret");
        String opsLogLevel = result.getString("ops_log_level");
        TomlArray registryUploadLanguagesArray = result.getArray("registry_upload_languages");
        TomlArray scanItemFilterArray = result.getArray("scan_item_filter");

        java.util.Set<String> scanItemFilter = defaults.scanItemFilter;
        java.util.List<String> registryUploadLanguages = defaults.registryUploadLanguages;

        if (registryUploadLanguagesArray != null) {
            java.util.List<String> parsed = new java.util.ArrayList<>();
            for (int i = 0; i < registryUploadLanguagesArray.size(); i++) {
                String value = registryUploadLanguagesArray.getString(i);
                if (value != null && !value.trim().isEmpty()) {
                    parsed.add(value.trim().toLowerCase());
                }
            }
            if (!parsed.isEmpty()) {
                registryUploadLanguages = parsed;
            }
        }

        if (scanItemFilterArray != null) {
            java.util.Set<String> parsed = new java.util.HashSet<>();
            for (int i = 0; i < scanItemFilterArray.size(); i++) {
                String value = scanItemFilterArray.getString(i);
                if (value != null && !value.trim().isEmpty()) {
                    parsed.add(value.trim().toLowerCase());
                }
            }
            scanItemFilter = parsed;
        }

        return new LatticeConfig(
            backendUrl != null ? backendUrl : defaults.backendUrl,
            apiToken != null ? apiToken : defaults.apiToken,
            serverId != null ? serverId : defaults.serverId,
            rconHost != null ? rconHost : defaults.rconHost,
            batchSize != null ? batchSize.intValue() : defaults.batchSize,
            flushIntervalMs != null ? flushIntervalMs : defaults.flushIntervalMs,
            spoolDir != null ? configDir.resolve(spoolDir) : defaults.spoolDir,
            contextWindowMs != null ? contextWindowMs : defaults.contextWindowMs,
            auditEnabled != null ? auditEnabled : defaults.auditEnabled,
            auditIntervalMinutes != null ? auditIntervalMinutes : defaults.auditIntervalMinutes,
            auditPlayersPerTick != null ? auditPlayersPerTick.intValue() : defaults.auditPlayersPerTick,
            scanEnabled != null ? scanEnabled : defaults.scanEnabled,
            scanIntervalMinutes != null ? scanIntervalMinutes : defaults.scanIntervalMinutes,
            scanContainersPerTick != null ? scanContainersPerTick.intValue() : defaults.scanContainersPerTick,
            scanRs2NetworksPerTick != null ? scanRs2NetworksPerTick.intValue() : defaults.scanRs2NetworksPerTick,
            scanRescanCooldownMinutes != null ? scanRescanCooldownMinutes : defaults.scanRescanCooldownMinutes,
            scanIncludeContainers != null ? scanIncludeContainers : defaults.scanIncludeContainers,
            scanIncludeRs2 != null ? scanIncludeRs2 : defaults.scanIncludeRs2,
            scanMaxAvgTickMs != null ? scanMaxAvgTickMs : defaults.scanMaxAvgTickMs,
            scanMaxOnlinePlayers != null ? scanMaxOnlinePlayers.intValue() : defaults.scanMaxOnlinePlayers,
            scanWorldOfflineEnabled != null ? scanWorldOfflineEnabled : defaults.scanWorldOfflineEnabled,
            scanSbOfflineEnabled != null ? scanSbOfflineEnabled : defaults.scanSbOfflineEnabled,
            scanRs2OfflineEnabled != null ? scanRs2OfflineEnabled : defaults.scanRs2OfflineEnabled,
            scanOfflineChunksPerTick != null ? scanOfflineChunksPerTick.intValue() : defaults.scanOfflineChunksPerTick,
            scanOfflineSourcesPerTick != null ? scanOfflineSourcesPerTick.intValue() : defaults.scanOfflineSourcesPerTick,
            scanOfflineWorkers != null ? scanOfflineWorkers.intValue() : defaults.scanOfflineWorkers,
            scanOfflineChunkIntervalMs != null ? scanOfflineChunkIntervalMs : defaults.scanOfflineChunkIntervalMs,
            scanIncludeOnlineRuntime != null ? scanIncludeOnlineRuntime : defaults.scanIncludeOnlineRuntime,
            registryUploadEnabled != null ? registryUploadEnabled : defaults.registryUploadEnabled,
            registryUploadChunkSize != null ? registryUploadChunkSize.intValue() : defaults.registryUploadChunkSize,
            registryUploadLanguages,
            scanItemFilter,
            opCommandTokenRequired != null ? opCommandTokenRequired : defaults.opCommandTokenRequired,
            opCommandTokenSecret != null ? opCommandTokenSecret : defaults.opCommandTokenSecret,
            opsLogLevel != null ? opsLogLevel : defaults.opsLogLevel
        );
    }

    private static LatticeConfig defaultConfig(Path configDir) {
        return new LatticeConfig(
            "http://127.0.0.1:3234",
            "",
            "server-01",
            "127.0.0.1",
            200,
            1000,
            configDir.resolve("lattice").resolve("spool"),
            2000,
            true,
            30,
            4,
            true,
            1440,
            2,
            1,
            1440,
            true,
            true,
            25,
            -1,
            true,
            true,
            true,
            1,
            1,
            1,
            1000,
            true,
            true,
            500,
            java.util.List.of("zh_cn", "en_us"),
            java.util.Collections.emptySet(),
            false,
            "",
            "INFO"
        );
    }

    private static String getString(JsonObject obj, String key, String fallback) {
        if (!obj.has(key)) {
            return fallback;
        }
        JsonElement element = obj.get(key);
        if (element == null || element.isJsonNull()) {
            return fallback;
        }
        try {
            String value = element.getAsString();
            if (value == null) {
                return fallback;
            }
            String trimmed = value.trim();
            return trimmed.isEmpty() ? fallback : trimmed;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static int getInt(JsonObject obj, String key, int fallback) {
        if (!obj.has(key)) {
            return fallback;
        }
        try {
            return obj.get(key).getAsInt();
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static long getLong(JsonObject obj, String key, long fallback) {
        if (!obj.has(key)) {
            return fallback;
        }
        try {
            return obj.get(key).getAsLong();
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static boolean getBoolean(JsonObject obj, String key, boolean fallback) {
        if (!obj.has(key)) {
            return fallback;
        }
        try {
            return obj.get(key).getAsBoolean();
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static java.util.List<String> getStringList(JsonObject obj, String key, java.util.List<String> fallback) {
        if (!obj.has(key) || !obj.get(key).isJsonArray()) {
            return fallback;
        }
        JsonArray array = obj.getAsJsonArray(key);
        java.util.List<String> out = new java.util.ArrayList<>();
        for (JsonElement element : array) {
            if (element == null || element.isJsonNull()) {
                continue;
            }
            try {
                String value = element.getAsString();
                if (value != null && !value.trim().isEmpty()) {
                    out.add(value.trim().toLowerCase());
                }
            } catch (Exception ignored) {
                // ignore
            }
        }
        return out.isEmpty() ? fallback : out;
    }

    private static java.util.Set<String> getStringSet(JsonObject obj, String key, java.util.Set<String> fallback) {
        if (!obj.has(key) || !obj.get(key).isJsonArray()) {
            return fallback;
        }
        JsonArray array = obj.getAsJsonArray(key);
        java.util.Set<String> out = new java.util.HashSet<>();
        for (JsonElement element : array) {
            if (element == null || element.isJsonNull()) {
                continue;
            }
            try {
                String value = element.getAsString();
                if (value != null && !value.trim().isEmpty()) {
                    out.add(value.trim().toLowerCase());
                }
            } catch (Exception ignored) {
                // ignore
            }
        }
        return out.isEmpty() ? fallback : out;
    }

    private static String normalizeLogLevel(String level) {
        if (level == null) {
            return "INFO";
        }
        String upper = level.trim().toUpperCase();
        return switch (upper) {
            case "ERROR", "WARN", "INFO", "DEBUG" -> upper;
            default -> "INFO";
        };
    }
}
