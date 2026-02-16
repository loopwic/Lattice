package com.lattice.logging;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.lattice.Lattice;
import com.lattice.config.LatticeConfig;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;

public final class StructuredOpsLogger {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final Object LOCK = new Object();
    private static final long MAX_FILE_BYTES = 50L * 1024L * 1024L;
    private static final int MAX_ROTATION = 5;

    private StructuredOpsLogger() {
    }

    public static void info(String event, Map<String, Object> fields) {
        log("INFO", event, fields);
    }

    public static void warn(String event, Map<String, Object> fields) {
        log("WARN", event, fields);
    }

    public static void error(String event, Map<String, Object> fields) {
        log("ERROR", event, fields);
    }

    public static void debug(String event, Map<String, Object> fields) {
        log("DEBUG", event, fields);
    }

    public static void log(String level, String event, Map<String, Object> fields) {
        if (!isEnabled(level)) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("timestamp_ms", System.currentTimeMillis());
        payload.put("level", level);
        payload.put("event", event);
        if (fields != null && !fields.isEmpty()) {
            payload.putAll(fields);
        }

        synchronized (LOCK) {
            Path logPath = resolveLogPath();
            try {
                Files.createDirectories(logPath.getParent());
                rotateIfNeeded(logPath);
                Files.writeString(
                    logPath,
                    GSON.toJson(payload) + "\n",
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
                );
            } catch (IOException e) {
                Lattice.LOGGER.debug("Write structured log failed", e);
            }
        }
    }

    private static boolean isEnabled(String level) {
        LatticeConfig config = Lattice.getConfig();
        String current = config != null ? config.opsLogLevel : "INFO";
        return levelPriority(level) >= levelPriority(current);
    }

    private static int levelPriority(String level) {
        String upper = level == null ? "" : level.trim().toUpperCase();
        return switch (upper) {
            case "DEBUG" -> 10;
            case "INFO" -> 20;
            case "WARN" -> 30;
            case "ERROR" -> 40;
            default -> 20;
        };
    }

    private static Path resolveLogPath() {
        Path gameDir = FabricLoader.getInstance().getGameDir();
        return gameDir.resolve("logs").resolve("lattice-ops.log");
    }

    private static void rotateIfNeeded(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        long size = Files.size(path);
        if (size < MAX_FILE_BYTES) {
            return;
        }
        for (int index = MAX_ROTATION; index >= 1; index--) {
            Path from = index == 1 ? path : path.resolveSibling("lattice-ops.log." + (index - 1));
            Path to = path.resolveSibling("lattice-ops.log." + index);
            if (!Files.exists(from)) {
                continue;
            }
            Files.deleteIfExists(to);
            Files.move(from, to);
        }
    }
}
