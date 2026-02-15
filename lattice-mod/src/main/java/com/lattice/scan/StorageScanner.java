package com.lattice.scan;

import com.lattice.Lattice;
import com.lattice.audit.AuditSnapshot;
import com.lattice.config.LatticeConfig;
import com.lattice.progress.TaskProgressReporter;
import com.lattice.scheduler.MonitorScheduler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.storage.LevelResource;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class StorageScanner {
    private static final Pattern REGION_FILE_PATTERN = Pattern.compile("^r\\.(-?\\d+)\\.(-?\\d+)\\.mca$");
    private static final String REASON_NO_TARGETS = "NO_TARGETS";
    private static final String REASON_WORLD_INDEX_FAILED = "WORLD_INDEX_FAILED";
    private static final String REASON_SB_DATA_UNAVAILABLE = "SB_DATA_UNAVAILABLE";
    private static final String REASON_RS2_DATA_UNAVAILABLE = "RS2_DATA_UNAVAILABLE";
    private static final String REASON_HEALTH_GUARD_BLOCKED = "HEALTH_GUARD_BLOCKED";
    private static final String REASON_PARTIAL_COMPLETED = "PARTIAL_COMPLETED";

    private final LatticeConfig config;
    private final Deque<ContainerTarget> containerQueue = new ArrayDeque<>();
    private final Deque<Rs2Target> rs2Queue = new ArrayDeque<>();
    private final Deque<OfflineChunkTarget> worldOfflineQueue = new ArrayDeque<>();
    private final Deque<OfflineDataTarget> sbOfflineQueue = new ArrayDeque<>();
    private final Deque<OfflineDataTarget> rs2OfflineQueue = new ArrayDeque<>();
    private final Map<String, Long> lastScanned = new HashMap<>();
    private final Set<String> itemFilter;
    private final SourceTotals sourceTotals = new SourceTotals();

    private long nextScanAtMs;
    private Field cachedBlockEntityField;
    private boolean cachedBlockEntityFieldIsMap;
    private boolean scanRunning;
    private boolean forceScanRequested;
    private int scanTotal;
    private int scanDone;
    private long scanLastReportMs;
    private int scanLastReportedDone;
    private String scanReasonCode;
    private String scanReasonMessage;
    private boolean rotateOfflineSource;

    public StorageScanner(LatticeConfig config) {
        this.config = config;
        this.itemFilter = config.scanItemFilter;
    }

    public boolean requestScanNow() {
        if (scanRunning || forceScanRequested || hasPendingTargets()) {
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

        if (!scanRunning && !isServerHealthy(server)) {
            setReason(REASON_HEALTH_GUARD_BLOCKED, "服务器负载保护触发，扫描已延后");
            reportScanProgress(now, false);
            return;
        }

        if ((nextScanAtMs == 0 || now >= nextScanAtMs) && !scanRunning && !hasPendingTargets()) {
            startScan(server, now);
            forceScanRequested = false;
            if (config.scanIntervalMinutes > 0) {
                nextScanAtMs = now + (config.scanIntervalMinutes * 60_000L);
            } else {
                nextScanAtMs = Long.MAX_VALUE;
            }
        }

        if (!scanRunning) {
            return;
        }

        int chunkBudget = Math.max(1, config.scanOfflineChunksPerTick);
        while (chunkBudget-- > 0 && !worldOfflineQueue.isEmpty()) {
            OfflineChunkTarget target = worldOfflineQueue.pollFirst();
            if (target == null) {
                break;
            }
            scanDone++;
            processWorldOfflineTarget(server, target, now);
        }

        int sourceBudget = Math.max(1, config.scanOfflineSourcesPerTick);
        while (sourceBudget-- > 0 && (!sbOfflineQueue.isEmpty() || !rs2OfflineQueue.isEmpty())) {
            OfflineDataTarget target;
            if (rotateOfflineSource) {
                target = pollOfflineTarget(rs2OfflineQueue, sbOfflineQueue);
            } else {
                target = pollOfflineTarget(sbOfflineQueue, rs2OfflineQueue);
            }
            rotateOfflineSource = !rotateOfflineSource;
            if (target == null) {
                break;
            }
            scanDone++;
            processOfflineDataTarget(target, now);
        }

        if (config.scanIncludeOnlineRuntime) {
            int containerBudget = Math.max(1, config.scanContainersPerTick);
            while (containerBudget-- > 0 && !containerQueue.isEmpty()) {
                ContainerTarget target = containerQueue.pollFirst();
                if (target == null) {
                    break;
                }
                scanDone++;
                processRuntimeContainerTarget(server, target, now);
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
        }

        reportScanProgress(now, false);
        if (!hasPendingTargets()) {
            scanRunning = false;
            reportScanProgress(now, true);
        }
    }

    public MonitorScheduler.TaskProgressSnapshot getProgress() {
        return new MonitorScheduler.TaskProgressSnapshot(scanRunning, scanTotal, scanDone);
    }

    private void startScan(MinecraftServer server, long now) {
        clearTargets();
        sourceTotals.reset();
        scanReasonCode = null;
        scanReasonMessage = null;

        boolean worldFailed = false;
        boolean sbFailed = false;
        boolean rs2Failed = false;

        if (config.scanWorldOfflineEnabled) {
            try {
                sourceTotals.worldContainers = enqueueWorldOfflineTargets(server);
            } catch (Exception e) {
                worldFailed = true;
                Lattice.LOGGER.warn("Offline world scan target build failed", e);
            }
        }

        if (config.scanSbOfflineEnabled) {
            try {
                sourceTotals.sbOffline = enqueueSbOfflineTargets(server);
            } catch (Exception e) {
                sbFailed = true;
                Lattice.LOGGER.warn("SB offline scan target build failed", e);
            }
        }

        if (config.scanRs2OfflineEnabled) {
            try {
                sourceTotals.rs2Offline = enqueueRs2OfflineTargets(server);
            } catch (Exception e) {
                rs2Failed = true;
                Lattice.LOGGER.warn("RS2 offline scan target build failed", e);
            }
        }

        if (config.scanIncludeOnlineRuntime) {
            sourceTotals.onlineRuntime = enqueueOnlineRuntimeTargets(server, now);
        }

        scanTotal = sourceTotals.totalTargets();
        scanDone = 0;
        scanRunning = scanTotal > 0;

        if (scanTotal == 0) {
            if (worldFailed) {
                setReason(REASON_WORLD_INDEX_FAILED, "世界区块索引失败，未找到可扫描目标");
            } else if (sbFailed) {
                setReason(REASON_SB_DATA_UNAVAILABLE, "SB 离线数据不可用");
            } else if (rs2Failed) {
                setReason(REASON_RS2_DATA_UNAVAILABLE, "RS2 离线数据不可用");
            } else {
                setReason(REASON_NO_TARGETS, "无可执行目标");
            }
        } else if (worldFailed || sbFailed || rs2Failed) {
            setReason(
                REASON_PARTIAL_COMPLETED,
                "部分离线来源不可用，已按可用来源继续扫描"
            );
        }

        reportScanProgress(now, true);
    }

    private void processRuntimeContainerTarget(MinecraftServer server, ContainerTarget target, long now) {
        ServerLevel level = server.getLevel(target.dimension);
        if (level == null || !level.hasChunkAt(target.pos)) {
            return;
        }
        BlockEntity blockEntity = level.getBlockEntity(target.pos);
        if (!(blockEntity instanceof Container container)) {
            return;
        }
        int sent = AuditSnapshot.enqueueContainerSnapshot(level, target.pos, container, target.storageMod, target.storageId, itemFilter);
        if (sent > 0) {
            lastScanned.put(target.storageId, now);
        }
    }

    private void processWorldOfflineTarget(MinecraftServer server, OfflineChunkTarget target, long now) {
        ServerLevel level = server.getLevel(target.dimension);
        if (level == null) {
            return;
        }

        Object chunk = loadChunk(level, target.chunkX, target.chunkZ);
        if (chunk == null) {
            return;
        }

        List<BlockEntity> entities = extractChunkBlockEntities(chunk);
        for (BlockEntity blockEntity : entities) {
            if (!(blockEntity instanceof Container container)) {
                continue;
            }
            BlockPos pos = blockEntity.getBlockPos();
            ResourceLocation typeId = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(blockEntity.getType());
            String modId = typeId != null ? typeId.getNamespace() : "unknown";
            String storageId = modId + ":" + level.dimension().location() + ":" + pos.getX() + "," + pos.getY() + "," + pos.getZ();
            if (shouldSkip(storageId, now)) {
                continue;
            }
            int sent = AuditSnapshot.enqueueContainerSnapshot(level, pos, container, modId, storageId, itemFilter);
            if (sent > 0) {
                lastScanned.put(storageId, now);
            }
        }
    }

    private void processOfflineDataTarget(OfflineDataTarget target, long now) {
        if (shouldSkip(target.storageId, now)) {
            return;
        }
        Map<String, Integer> counts = readNbtItemCounts(target.path, target.storageMod);
        if (counts.isEmpty()) {
            return;
        }
        int sent = AuditSnapshot.enqueueStorageSnapshotCounts(
            counts,
            target.storageMod,
            target.storageId,
            null,
            null
        );
        if (sent > 0) {
            lastScanned.put(target.storageId, now);
        }
    }

    private int enqueueOnlineRuntimeTargets(MinecraftServer server, long now) {
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
                    if (!shouldSkip(storageId, now) && seenStorage.add(storageId)) {
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

        return containerQueue.size() + rs2Queue.size();
    }

    private int enqueueWorldOfflineTargets(MinecraftServer server) throws Exception {
        Path worldRoot = server.getWorldPath(LevelResource.ROOT);
        if (worldRoot == null) {
            throw new IllegalStateException("world root path unavailable");
        }

        Set<String> seenChunks = new HashSet<>();
        for (ServerLevel level : server.getAllLevels()) {
            ResourceLocation dimId = level.dimension().location();
            Path dimPath = resolveDimensionPath(worldRoot, dimId);
            Path regionDir = dimPath.resolve("region");
            if (!Files.isDirectory(regionDir)) {
                continue;
            }
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(regionDir, "r.*.*.mca")) {
                for (Path regionFile : stream) {
                    enqueueRegionChunks(dimId, regionFile, seenChunks);
                }
            }
        }
        return worldOfflineQueue.size();
    }

    private int enqueueSbOfflineTargets(MinecraftServer server) throws Exception {
        Path worldRoot = server.getWorldPath(LevelResource.ROOT);
        if (worldRoot == null || !Files.exists(worldRoot)) {
            throw new IllegalStateException("world root path unavailable");
        }
        return enqueueOfflineDataTargets(
            worldRoot,
            new String[]{"sophisticated", "backpack"},
            "sophisticatedbackpacks",
            "sb-offline",
            sbOfflineQueue
        );
    }

    private int enqueueRs2OfflineTargets(MinecraftServer server) throws Exception {
        Path worldRoot = server.getWorldPath(LevelResource.ROOT);
        if (worldRoot == null || !Files.exists(worldRoot)) {
            throw new IllegalStateException("world root path unavailable");
        }
        return enqueueOfflineDataTargets(
            worldRoot,
            new String[]{"refinedstorage", "rs2"},
            "rs2",
            "rs2-offline",
            rs2OfflineQueue
        );
    }

    private int enqueueOfflineDataTargets(
        Path worldRoot,
        String[] keywords,
        String storageMod,
        String storagePrefix,
        Deque<OfflineDataTarget> queue
    ) throws Exception {
        List<Path> roots = List.of(
            worldRoot.resolve("data"),
            worldRoot.resolve("playerdata"),
            worldRoot
        );
        Set<String> seenStorageIds = new HashSet<>();
        for (Path root : roots) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> stream = Files.walk(root, 8)) {
                stream
                    .filter(Files::isRegularFile)
                    .filter(this::isNbtLikeFile)
                    .filter(path -> pathContainsAny(path, keywords))
                    .limit(10_000)
                    .forEach(path -> {
                        String relative;
                        try {
                            relative = worldRoot.relativize(path).toString();
                        } catch (Exception ignored) {
                            relative = path.getFileName().toString();
                        }
                        String normalized = relative.replace('\\', '/').toLowerCase();
                        String storageId = storagePrefix + ":" + normalized;
                        if (seenStorageIds.add(storageId)) {
                            queue.addLast(new OfflineDataTarget(path, storageMod, storageId));
                        }
                    });
            }
        }
        return queue.size();
    }

    private void enqueueRegionChunks(
        ResourceLocation dimensionId,
        Path regionFile,
        Set<String> seenChunks
    ) throws Exception {
        Matcher matcher = REGION_FILE_PATTERN.matcher(regionFile.getFileName().toString());
        if (!matcher.matches()) {
            return;
        }
        int regionX = Integer.parseInt(matcher.group(1));
        int regionZ = Integer.parseInt(matcher.group(2));
        byte[] header = readRegionHeader(regionFile);
        if (header.length < 4096) {
            return;
        }
        for (int i = 0; i < 1024; i++) {
            int index = i * 4;
            int offset = ((header[index] & 0xFF) << 16)
                | ((header[index + 1] & 0xFF) << 8)
                | (header[index + 2] & 0xFF);
            int sectors = header[index + 3] & 0xFF;
            if (offset == 0 || sectors == 0) {
                continue;
            }
            int localX = i % 32;
            int localZ = i / 32;
            int chunkX = regionX * 32 + localX;
            int chunkZ = regionZ * 32 + localZ;
            String key = dimensionId + ":" + chunkX + "," + chunkZ;
            if (seenChunks.add(key)) {
                worldOfflineQueue.addLast(new OfflineChunkTarget(
                    ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, dimensionId),
                    chunkX,
                    chunkZ
                ));
            }
        }
    }

    private byte[] readRegionHeader(Path regionFile) throws Exception {
        try (InputStream input = Files.newInputStream(regionFile)) {
            return input.readNBytes(4096);
        }
    }

    private Path resolveDimensionPath(Path worldRoot, ResourceLocation dimId) {
        if (Level.OVERWORLD.location().equals(dimId)) {
            return worldRoot;
        }
        if (Level.NETHER.location().equals(dimId)) {
            return worldRoot.resolve("DIM-1");
        }
        if (Level.END.location().equals(dimId)) {
            return worldRoot.resolve("DIM1");
        }
        return worldRoot
            .resolve("dimensions")
            .resolve(dimId.getNamespace())
            .resolve(dimId.getPath());
    }

    private OfflineDataTarget pollOfflineTarget(Deque<OfflineDataTarget> primary, Deque<OfflineDataTarget> fallback) {
        OfflineDataTarget target = primary.pollFirst();
        if (target != null) {
            return target;
        }
        return fallback.pollFirst();
    }

    private boolean hasPendingTargets() {
        return !containerQueue.isEmpty()
            || !rs2Queue.isEmpty()
            || !worldOfflineQueue.isEmpty()
            || !sbOfflineQueue.isEmpty()
            || !rs2OfflineQueue.isEmpty();
    }

    private void clearTargets() {
        containerQueue.clear();
        rs2Queue.clear();
        worldOfflineQueue.clear();
        sbOfflineQueue.clear();
        rs2OfflineQueue.clear();
        rotateOfflineSource = false;
    }

    private void reportScanProgress(long now, boolean force) {
        if (!force) {
            if (now - scanLastReportMs < 2000 && scanDone - scanLastReportedDone < 20) {
                return;
            }
        }
        scanLastReportMs = now;
        scanLastReportedDone = scanDone;
        TaskProgressReporter.report(
            config,
            "scan",
            scanRunning,
            scanTotal,
            scanDone,
            scanReasonCode,
            scanReasonMessage,
            sourceTotals.toPayload()
        );
    }

    private void setReason(String code, String message) {
        this.scanReasonCode = code;
        this.scanReasonMessage = message;
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
                result.addAll(extractChunkBlockEntities(chunk));
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

    private List<BlockEntity> extractChunkBlockEntities(Object chunk) {
        Object beMapObj = invokeFirst(chunk, new String[]{"getBlockEntities", "getBlockEntityMap"});
        if (!(beMapObj instanceof Map<?, ?> beMap)) {
            return List.of();
        }
        List<BlockEntity> result = new ArrayList<>();
        for (Object value : beMap.values()) {
            if (value instanceof BlockEntity be) {
                result.add(be);
            }
        }
        return result;
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
        return invokeFirst(holder, new String[]{"getTickingChunk", "getFullChunk", "getChunkToSend", "getChunk"});
    }

    private Object loadChunk(ServerLevel level, int chunkX, int chunkZ) {
        Object chunkSource = level.getChunkSource();
        Object chunk = invoke(chunkSource, "getChunkNow", new Class<?>[]{int.class, int.class}, new Object[]{chunkX, chunkZ});
        if (chunk != null) {
            return chunk;
        }
        chunk = invoke(
            chunkSource,
            "getChunk",
            new Class<?>[]{int.class, int.class, boolean.class},
            new Object[]{chunkX, chunkZ, false}
        );
        if (chunk != null) {
            return chunk;
        }
        chunk = invoke(chunkSource, "getChunk", new Class<?>[]{int.class, int.class}, new Object[]{chunkX, chunkZ});
        if (chunk != null) {
            return chunk;
        }
        try {
            return level.getChunk(chunkX, chunkZ);
        } catch (Exception ignored) {
            return null;
        }
    }

    private Map<String, Integer> readNbtItemCounts(Path file, String source) {
        try {
            CompoundTag root = readCompressedTag(file);
            if (root == null) {
                return Map.of();
            }
            Map<String, Integer> counts = new HashMap<>();
            collectNbtItems(root, counts);
            return counts;
        } catch (Exception e) {
            if ("sophisticatedbackpacks".equals(source)) {
                setReason(REASON_PARTIAL_COMPLETED, "SB 离线数据部分解析失败");
            } else if ("rs2".equals(source)) {
                setReason(REASON_PARTIAL_COMPLETED, "RS2 离线数据部分解析失败");
            }
            Lattice.LOGGER.debug("Offline storage parse failed: {}", file, e);
            return Map.of();
        }
    }

    private CompoundTag readCompressedTag(Path file) throws Exception {
        Object accounter = null;
        try {
            Method unlimited = NbtAccounter.class.getMethod("unlimitedHeap");
            accounter = unlimited.invoke(null);
        } catch (Exception ignored) {
            // try without accounter
        }

        if (accounter != null) {
            Object value = invokeStatic(
                NbtIo.class,
                "readCompressed",
                new Class<?>[]{Path.class, NbtAccounter.class},
                new Object[]{file, accounter}
            );
            if (value instanceof CompoundTag tag) {
                return tag;
            }
        }

        try (InputStream input = Files.newInputStream(file)) {
            if (accounter != null) {
                Object value = invokeStatic(
                    NbtIo.class,
                    "readCompressed",
                    new Class<?>[]{InputStream.class, NbtAccounter.class},
                    new Object[]{input, accounter}
                );
                if (value instanceof CompoundTag tag) {
                    return tag;
                }
            }
        }

        try (InputStream input = Files.newInputStream(file)) {
            Object value = invokeStatic(
                NbtIo.class,
                "readCompressed",
                new Class<?>[]{InputStream.class},
                new Object[]{input}
            );
            if (value instanceof CompoundTag tag) {
                return tag;
            }
        }
        return null;
    }

    private void collectNbtItems(Tag tag, Map<String, Integer> counts) {
        if (tag == null) {
            return;
        }

        if (tag instanceof CompoundTag compound) {
            collectItemIfStackLike(compound, counts);
            for (String key : compound.getAllKeys()) {
                collectNbtItems(compound.get(key), counts);
            }
            return;
        }

        if (tag instanceof ListTag list) {
            for (int i = 0; i < list.size(); i++) {
                collectNbtItems(list.get(i), counts);
            }
        }
    }

    private void collectItemIfStackLike(CompoundTag compound, Map<String, Integer> counts) {
        String itemId = compound.getString("id");
        if (itemId == null || itemId.isBlank() || !itemId.contains(":")) {
            return;
        }

        int count = compound.getInt("Count");
        if (count <= 0) {
            count = compound.getInt("count");
        }
        if (count <= 0) {
            count = compound.getInt("amount");
        }
        if (count <= 0) {
            return;
        }

        String normalizedId = itemId.trim().toLowerCase();
        if (itemFilter != null && !itemFilter.isEmpty() && !itemFilter.contains(normalizedId)) {
            return;
        }
        counts.merge(normalizedId, count, Integer::sum);
    }

    private boolean pathContainsAny(Path path, String[] keywords) {
        String lower = path.toString().toLowerCase();
        for (String keyword : keywords) {
            if (lower.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private boolean isNbtLikeFile(Path path) {
        String name = path.getFileName().toString().toLowerCase();
        return name.endsWith(".dat") || name.endsWith(".nbt");
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

    private Object invoke(Object target, String method, Class<?>[] types, Object[] args) {
        try {
            Method m = target.getClass().getMethod(method, types);
            return m.invoke(target, args);
        } catch (Exception ignored) {
            return null;
        }
    }

    private Object invokeStatic(Class<?> owner, String method, Class<?>[] types, Object[] args) {
        try {
            Method m = owner.getMethod(method, types);
            return m.invoke(null, args);
        } catch (Exception ignored) {
            return null;
        }
    }

    private record ContainerTarget(ResourceKey<Level> dimension, BlockPos pos, String storageMod, String storageId) {
    }

    private record Rs2Target(Object network, String storageId) {
    }

    private record OfflineChunkTarget(ResourceKey<Level> dimension, int chunkX, int chunkZ) {
    }

    private record OfflineDataTarget(Path path, String storageMod, String storageId) {
    }

    private static final class SourceTotals {
        int worldContainers;
        int sbOffline;
        int rs2Offline;
        int onlineRuntime;

        int totalTargets() {
            return worldContainers + sbOffline + rs2Offline + onlineRuntime;
        }

        TaskProgressReporter.SourceTotalsPayload toPayload() {
            return new TaskProgressReporter.SourceTotalsPayload(
                worldContainers,
                sbOffline,
                rs2Offline,
                onlineRuntime
            );
        }

        void reset() {
            worldContainers = 0;
            sbOffline = 0;
            rs2Offline = 0;
            onlineRuntime = 0;
        }
    }
}
