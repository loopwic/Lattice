package com.lattice.config;

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
    public final boolean scanIncludeOnlineRuntime;
    public final boolean registryUploadEnabled;
    public final int registryUploadChunkSize;
    public final java.util.List<String> registryUploadLanguages;
    public final java.util.Set<String> scanItemFilter;

    private LatticeConfig(String backendUrl,
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
                              boolean scanIncludeOnlineRuntime,
                              boolean registryUploadEnabled,
                              int registryUploadChunkSize,
                              java.util.List<String> registryUploadLanguages,
                              java.util.Set<String> scanItemFilter) {
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
        this.scanIncludeOnlineRuntime = scanIncludeOnlineRuntime;
        this.registryUploadEnabled = registryUploadEnabled;
        this.registryUploadChunkSize = registryUploadChunkSize;
        this.registryUploadLanguages = registryUploadLanguages;
        this.scanItemFilter = scanItemFilter;
    }

    public static LatticeConfig load() {
        Path configDir = FabricLoader.getInstance().getConfigDir();
        Path path = configDir.resolve("lattice.toml");
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
            Boolean scanIncludeOnlineRuntime = result.getBoolean("scan_include_online_runtime");
            Boolean registryUploadEnabled = result.getBoolean("registry_upload_enabled");
            Long registryUploadChunkSize = result.getLong("registry_upload_chunk_size");
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
                scanIncludeOnlineRuntime != null ? scanIncludeOnlineRuntime : defaults.scanIncludeOnlineRuntime,
                registryUploadEnabled != null ? registryUploadEnabled : defaults.registryUploadEnabled,
                registryUploadChunkSize != null ? registryUploadChunkSize.intValue() : defaults.registryUploadChunkSize,
                registryUploadLanguages,
                scanItemFilter
            );
        } catch (Exception e) {
            LOGGER.warn("Failed to load config, using defaults", e);
            return defaults;
        }
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
            45,
            -1,
            true,
            true,
            true,
            8,
            2,
            true,
            true,
            500,
            java.util.List.of("zh_cn", "en_us"),
            java.util.Collections.emptySet()
        );
    }

    private String toTomlString() {
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
            "scan_include_online_runtime = " + scanIncludeOnlineRuntime,
            "registry_upload_enabled = " + registryUploadEnabled,
            "registry_upload_chunk_size = " + registryUploadChunkSize,
            "registry_upload_languages = [" + languages + "]",
            "scan_item_filter = [" + filter + "]"
        ) + "\n";
    }
}
