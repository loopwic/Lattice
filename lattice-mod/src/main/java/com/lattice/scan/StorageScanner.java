package com.lattice.scan;

import com.lattice.Lattice;
import com.lattice.audit.AuditSnapshot;
import com.lattice.config.LatticeConfig;
import com.lattice.progress.TaskProgressReporter;
import com.lattice.scheduler.MonitorScheduler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class StorageScanner {
    private final LatticeConfig config;
    private final Deque<ContainerTarget> containerQueue = new ArrayDeque<>();
    private final Deque<Rs2Target> rs2Queue = new ArrayDeque<>();
    private final Map<String, Long> lastScanned = new HashMap<>();
    private final Set<String> itemFilter;

    private long nextScanAtMs;
    private Field cachedBlockEntityField;
    private boolean cachedBlockEntityFieldIsMap;
    private boolean scanRunning;
    private boolean forceScanRequested;
    private int scanTotal;
    private int scanDone;
    private long scanLastReportMs;
    private int scanLastReportedDone;

    public StorageScanner(LatticeConfig config) {
        this.config = config;
        this.itemFilter = config.scanItemFilter;
    }

    public boolean requestScanNow() {
        if (scanRunning || forceScanRequested || !containerQueue.isEmpty() || !rs2Queue.isEmpty()) {
            return false;
        }
        forceScanRequested = true;
        nextScanAtMs = 0;
        return true;
    }

    public void tick(MinecraftServer server, long now) {
        if (!config.scanEnabled && !forceScanRequested) {
            return;
        }
        if (!isServerHealthy(server)) {
            return;
        }
        if (nextScanAtMs == 0 || now >= nextScanAtMs) {
            if (containerQueue.isEmpty() && rs2Queue.isEmpty()) {
                enqueueTargets(server, now);
                scanTotal = containerQueue.size() + rs2Queue.size();
                scanDone = 0;
                scanRunning = scanTotal > 0;
                reportScanProgress(now, true);
                forceScanRequested = false;
                if (config.scanIntervalMinutes > 0) {
                    nextScanAtMs = now + (config.scanIntervalMinutes * 60_000L);
                } else {
                    nextScanAtMs = Long.MAX_VALUE;
                }
            }
        }

        int containerBudget = Math.max(1, config.scanContainersPerTick);
        while (containerBudget-- > 0 && !containerQueue.isEmpty()) {
            ContainerTarget target = containerQueue.pollFirst();
            if (target == null) {
                break;
            }
            scanDone++;
            ServerLevel level = server.getLevel(target.dimension);
            if (level == null || !level.hasChunkAt(target.pos)) {
                continue;
            }
            BlockEntity blockEntity = level.getBlockEntity(target.pos);
            if (!(blockEntity instanceof Container container)) {
                continue;
            }
            int sent = AuditSnapshot.enqueueContainerSnapshot(level, target.pos, container, target.storageMod, target.storageId, itemFilter);
            if (sent > 0) {
                lastScanned.put(target.storageId, now);
            }
        }

        int rs2Budget = Math.max(0, config.scanRs2NetworksPerTick);
        while (rs2Budget-- > 0 && !rs2Queue.isEmpty()) {
            Rs2Target target = rs2Queue.pollFirst();
            if (target == null) {
                break;
            }
            scanDone++;
            int sent = AuditSnapshot.enqueueRs2Snapshot(target.network, target.storageId, itemFilter);
            if (sent > 0) {
                lastScanned.put(target.storageId, now);
            }
        }

        if (scanRunning) {
            reportScanProgress(now, false);
            if (containerQueue.isEmpty() && rs2Queue.isEmpty()) {
                scanRunning = false;
                reportScanProgress(now, true);
            }
        }
    }

    private void enqueueTargets(MinecraftServer server, long now) {
        Set<String> seenStorage = new HashSet<>();
        Set<Integer> seenRs2Network = new HashSet<>();

        for (ServerLevel level : server.getAllLevels()) {
            List<BlockEntity> blockEntities = collectLoadedBlockEntities(level);
            for (BlockEntity blockEntity : blockEntities) {
                if (blockEntity == null) {
                    continue;
                }
                BlockPos pos = blockEntity.getBlockPos();
                ResourceLocation typeId = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(blockEntity.getType());
                String modId = typeId != null ? typeId.getNamespace() : "unknown";
                String storageId = modId + ":" + level.dimension().location() + ":" + pos.getX() + "," + pos.getY() + "," + pos.getZ();

                if (config.scanIncludeContainers && blockEntity instanceof Container) {
                    if (shouldSkip(storageId, now)) {
                        continue;
                    }
                    if (seenStorage.add(storageId)) {
                        containerQueue.addLast(new ContainerTarget(level.dimension(), pos, modId, storageId));
                    }
                }

                if (config.scanIncludeRs2) {
                    Object network = resolveRs2Network(blockEntity);
                    if (network != null) {
                        int networkId = System.identityHashCode(network);
                        if (seenRs2Network.add(networkId)) {
                            String netStorageId = "rs2-net:" + networkId;
                            if (!shouldSkip(netStorageId, now)) {
                                rs2Queue.addLast(new Rs2Target(network, netStorageId));
                            }
                        }
                    }
                }
            }
        }
    }

    public MonitorScheduler.TaskProgressSnapshot getProgress() {
        return new MonitorScheduler.TaskProgressSnapshot(scanRunning, scanTotal, scanDone);
    }

    private void reportScanProgress(long now, boolean force) {
        if (!force) {
            if (now - scanLastReportMs < 2000 && scanDone - scanLastReportedDone < 20) {
                return;
            }
        }
        scanLastReportMs = now;
        scanLastReportedDone = scanDone;
        TaskProgressReporter.report(config, "scan", scanRunning, scanTotal, scanDone);
    }

    private boolean shouldSkip(String storageId, long now) {
        long cooldownMs = config.scanRescanCooldownMinutes * 60_000L;
        if (cooldownMs <= 0) {
            return false;
        }
        Long last = lastScanned.get(storageId);
        return last != null && now - last < cooldownMs;
    }

    private boolean isServerHealthy(MinecraftServer server) {
        if (config.scanMaxOnlinePlayers >= 0) {
            int online = server.getPlayerList().getPlayerCount();
            if (online > config.scanMaxOnlinePlayers) {
                return false;
            }
        }
        if (config.scanMaxAvgTickMs > 0) {
            Double avg = readAverageTickMs(server);
            if (avg != null && avg > config.scanMaxAvgTickMs) {
                return false;
            }
        }
        return true;
    }

    private Double readAverageTickMs(MinecraftServer server) {
        try {
            Object value = invokeNoArg(server, "getAverageTickTime");
            if (value instanceof Number number) {
                return number.doubleValue();
            }
        } catch (Exception ignored) {
            // fallback below
        }
        try {
            for (Field field : server.getClass().getDeclaredFields()) {
                String name = field.getName().toLowerCase();
                if (!name.contains("tick") || !name.contains("avg")) {
                    continue;
                }
                field.setAccessible(true);
                Object value = field.get(server);
                if (value instanceof Number number) {
                    return number.doubleValue();
                }
            }
        } catch (Exception ignored) {
            // ignore
        }
        return null;
    }

    private List<BlockEntity> collectLoadedBlockEntities(ServerLevel level) {
        List<BlockEntity> result = new ArrayList<>();
        Collection<BlockEntity> direct = getBlockEntityCollection(level);
        if (direct != null) {
            result.addAll(direct);
            return result;
        }

        try {
            Object chunkSource = level.getChunkSource();
            Object chunkMap = resolveChunkMap(chunkSource);
            if (chunkMap == null) {
                return result;
            }
            Object holdersObj = invokeNoArg(chunkMap, "getChunks");
            if (!(holdersObj instanceof Iterable<?> holders)) {
                return result;
            }
            for (Object holder : holders) {
                Object chunk = resolveChunk(holder);
                if (chunk == null) {
                    continue;
                }
                Object beMapObj = invokeFirst(chunk, new String[]{"getBlockEntities", "getBlockEntityMap"});
                if (!(beMapObj instanceof Map<?, ?> beMap)) {
                    continue;
                }
                for (Object value : beMap.values()) {
                    if (value instanceof BlockEntity be) {
                        result.add(be);
                    }
                }
            }
        } catch (Exception e) {
            Lattice.LOGGER.debug("Storage scan fallback failed", e);
        }

        return result;
    }

    private Collection<BlockEntity> getBlockEntityCollection(ServerLevel level) {
        try {
            if (cachedBlockEntityField != null) {
                Object value = cachedBlockEntityField.get(level);
                return toBlockEntityCollection(value, cachedBlockEntityFieldIsMap);
            }
            Class<?> cls = level.getClass();
            while (cls != null) {
                for (Field field : cls.getDeclaredFields()) {
                    String name = field.getName().toLowerCase();
                    if (!name.contains("blockentity")) {
                        continue;
                    }
                    field.setAccessible(true);
                    Object value = field.get(level);
                    Collection<BlockEntity> resolved = toBlockEntityCollection(value, value instanceof Map<?, ?>);
                    if (resolved != null) {
                        cachedBlockEntityField = field;
                        cachedBlockEntityFieldIsMap = value instanceof Map<?, ?>;
                        return resolved;
                    }
                }
                cls = cls.getSuperclass();
            }
        } catch (Exception e) {
            Lattice.LOGGER.debug("Block entity reflection failed", e);
        }
        return null;
    }

    private Collection<BlockEntity> toBlockEntityCollection(Object value, boolean isMap) {
        if (value == null) {
            return null;
        }
        if (isMap && value instanceof Map<?, ?> map) {
            List<BlockEntity> result = new ArrayList<>();
            for (Object v : map.values()) {
                if (v instanceof BlockEntity be) {
                    result.add(be);
                }
            }
            return result;
        }
        if (value instanceof Collection<?> col) {
            List<BlockEntity> result = new ArrayList<>();
            for (Object v : col) {
                if (v instanceof BlockEntity be) {
                    result.add(be);
                }
            }
            return result;
        }
        return null;
    }

    private Object resolveChunkMap(Object chunkSource) {
        try {
            Field field = chunkSource.getClass().getDeclaredField("chunkMap");
            field.setAccessible(true);
            return field.get(chunkSource);
        } catch (Exception ignored) {
            // fallback below
        }
        try {
            for (Field field : chunkSource.getClass().getDeclaredFields()) {
                if (!field.getType().getSimpleName().toLowerCase().contains("chunkmap")) {
                    continue;
                }
                field.setAccessible(true);
                Object value = field.get(chunkSource);
                if (value != null) {
                    return value;
                }
            }
        } catch (Exception ignored) {
            // ignore
        }
        return null;
    }

    private Object resolveChunk(Object holder) {
        if (holder == null) {
            return null;
        }
        Object chunk = invokeFirst(holder, new String[]{"getTickingChunk", "getFullChunk", "getChunkToSend", "getChunk"});
        return chunk;
    }

    private Object resolveRs2Network(BlockEntity blockEntity) {
        try {
            return invokeNoArg(blockEntity, "getNetworkForItem");
        } catch (Exception ignored) {
            // fallback to getNetwork()
        }
        try {
            return invokeNoArg(blockEntity, "getNetwork");
        } catch (Exception ignored) {
            return null;
        }
    }

    private Object invokeNoArg(Object target, String method) throws Exception {
        Method m = target.getClass().getMethod(method);
        return m.invoke(target);
    }

    private Object invokeFirst(Object target, String[] methods) {
        for (String name : methods) {
            try {
                Method m = target.getClass().getMethod(name);
                return m.invoke(target);
            } catch (Exception ignored) {
                // try next
            }
        }
        return null;
    }

    private record ContainerTarget(ResourceKey<Level> dimension, BlockPos pos, String storageMod, String storageId) {
    }

    private record Rs2Target(Object network, String storageId) {
    }
}
