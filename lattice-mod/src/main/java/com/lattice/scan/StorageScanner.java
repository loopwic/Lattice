package com.lattice.scan;

import com.lattice.Lattice;
import com.lattice.audit.AuditSnapshot;
import com.lattice.config.LatticeConfig;
import com.lattice.logging.StructuredOpsLogger;
import com.lattice.progress.TaskProgressReporter;
import com.lattice.scheduler.MonitorScheduler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
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

import java.io.ByteArrayInputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
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
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;
import java.nio.channels.FileChannel;

public final class StorageScanner {
    private static final Pattern REGION_FILE_PATTERN = Pattern.compile("^r\\.(-?\\d+)\\.(-?\\d+)\\.mca$");
    private static final AtomicInteger SCAN_WORKER_ID = new AtomicInteger(1);
    private static final String REASON_NO_TARGETS = "NO_TARGETS";
    private static final String REASON_WORLD_INDEX_FAILED = "WORLD_INDEX_FAILED";
    private static final String REASON_SB_DATA_UNAVAILABLE = "SB_DATA_UNAVAILABLE";
    private static final String REASON_RS2_DATA_UNAVAILABLE = "RS2_DATA_UNAVAILABLE";
    private static final String REASON_HEALTH_GUARD_BLOCKED = "HEALTH_GUARD_BLOCKED";
    private static final String REASON_PARTIAL_COMPLETED = "PARTIAL_COMPLETED";
    private static final String PHASE_INDEXING = "INDEXING";
    private static final String PHASE_OFFLINE_WORLD = "OFFLINE_WORLD";
    private static final String PHASE_OFFLINE_SB = "OFFLINE_SB";
    private static final String PHASE_OFFLINE_RS2 = "OFFLINE_RS2";
    private static final String PHASE_RUNTIME = "RUNTIME";
    private static final String PHASE_COMPLETED = "COMPLETED";
    private static final String PHASE_DEGRADED = "DEGRADED";
    private static final int MAX_NBT_RECURSION_DEPTH = 8;
    private static final int WORLD_OFFLINE_QUEUE_LIMIT = 50_000;

    private volatile LatticeConfig config;
    private final Deque<ContainerTarget> containerQueue = new ArrayDeque<>();
    private final Deque<Rs2Target> rs2Queue = new ArrayDeque<>();
    private final Deque<RegionDirTarget> worldRegionDirQueue = new ArrayDeque<>();
    private final Deque<RegionFileTarget> worldRegionFileQueue = new ArrayDeque<>();
    private final Deque<WorldSnapshotTarget> worldOfflineQueue = new ArrayDeque<>();
    private final Deque<SbOfflineTarget> sbOfflineQueue = new ArrayDeque<>();
    private final Deque<OfflineDataTarget> rs2OfflineQueue = new ArrayDeque<>();
    private final Map<String, Long> lastScanned = new HashMap<>();
    private volatile Set<String> itemFilter;
    private final SourceTotals sourceTotals = new SourceTotals();
    private final ExecutorService scanExecutor;
    private final CompletionService<WorldTaskResult> worldRegionCompletion;
    private final CompletionService<OfflineDataTaskResult> offlineDataCompletion;
    private final int maxRegionInFlight;

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
    private String scanPhase;
    private String scanTraceId;
    private boolean rotateOfflineSource;
    private int worldRegionInFlight;
    private int offlineDataInFlight;
    private final DoneBySource doneBySource = new DoneBySource();
    private long scanStartedAtMs;
    private long nextWorldProcessAtMs;

    public StorageScanner(LatticeConfig config) {
        this.config = config;
        this.itemFilter = config.scanItemFilter;
        int cpus = Runtime.getRuntime().availableProcessors();
        int configuredWorkers = config.scanOfflineWorkers > 0 ? config.scanOfflineWorkers : 4;
        this.maxRegionInFlight = Math.max(1, Math.min(Math.min(16, cpus), configuredWorkers));
        this.scanExecutor = Executors.newFixedThreadPool(maxRegionInFlight, new ScanWorkerFactory());
        this.worldRegionCompletion = new ExecutorCompletionService<>(scanExecutor);
        this.offlineDataCompletion = new ExecutorCompletionService<>(scanExecutor);
    }

    public void updateConfig(LatticeConfig next) {
        if (next == null) {
            return;
        }
        this.config = next;
        this.itemFilter = next.scanItemFilter;
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
        LatticeConfig snapshot = this.config;
        if (!snapshot.scanEnabled && !forceScanRequested) {
            return;
        }

        if (!scanRunning && !isServerHealthy(server, snapshot)) {
            setReason(REASON_HEALTH_GUARD_BLOCKED, "服务器负载保护触发，扫描已延后");
            reportScanProgress(now, false);
            return;
        }

        if ((nextScanAtMs == 0 || now >= nextScanAtMs) && !scanRunning && !hasPendingTargets()) {
            startScan(server, now);
            forceScanRequested = false;
            if (snapshot.scanIntervalMinutes > 0) {
                nextScanAtMs = now + (snapshot.scanIntervalMinutes * 60_000L);
            } else {
                nextScanAtMs = Long.MAX_VALUE;
            }
        }

        if (!scanRunning) {
            return;
        }
        long tickStartNs = System.nanoTime();

        int regionDirBudget = Math.max(1, snapshot.scanOfflineSourcesPerTick);
        while (regionDirBudget-- > 0 && worldRegionInFlight < maxRegionInFlight && submitNextWorldRegionDirectory()) {
            // region directory indexing is handled off the main thread.
        }

        int regionFileBudget = Math.max(1, snapshot.scanOfflineSourcesPerTick);
        while (regionFileBudget-- > 0 && worldRegionInFlight < maxRegionInFlight && submitNextWorldRegionFile()) {
            // region files are parsed off the main thread.
        }

        int regionResultBudget = Math.max(1, snapshot.scanOfflineSourcesPerTick * maxRegionInFlight);
        while (regionResultBudget-- > 0 && collectRegionResult()) {
            // consume completed background region tasks incrementally.
        }

        int chunkBudget = Math.max(1, snapshot.scanOfflineChunksPerTick);
        while (chunkBudget-- > 0 && !worldOfflineQueue.isEmpty()) {
            if (now < nextWorldProcessAtMs) {
                break;
            }
            WorldSnapshotTarget target = worldOfflineQueue.pollFirst();
            if (target == null) {
                break;
            }
            scanDone++;
            scanPhase = PHASE_OFFLINE_WORLD;
            processWorldOfflineTarget(target, now);
            doneBySource.worldContainers++;
            long interval = Math.max(1L, snapshot.scanOfflineChunkIntervalMs);
            nextWorldProcessAtMs = now + interval;
        }

        int sourceBudget = Math.max(1, snapshot.scanOfflineSourcesPerTick);
        while (sourceBudget-- > 0 && submitNextOfflineDataTask(now)) {
            // offline data parsing is handled off the main thread.
        }

        int sourceResultBudget = Math.max(1, snapshot.scanOfflineSourcesPerTick * maxRegionInFlight);
        while (sourceResultBudget-- > 0 && collectOfflineDataResult(now)) {
            // consume completed offline data parsing tasks incrementally.
        }

        if (snapshot.scanIncludeOnlineRuntime) {
            int containerBudget = Math.max(1, snapshot.scanContainersPerTick);
            while (containerBudget-- > 0 && !containerQueue.isEmpty()) {
                ContainerTarget target = containerQueue.pollFirst();
                if (target == null) {
                    break;
                }
                scanDone++;
                scanPhase = PHASE_RUNTIME;
                processRuntimeContainerTarget(server, target, now);
                doneBySource.onlineRuntime++;
            }

            int rs2Budget = Math.max(0, snapshot.scanRs2NetworksPerTick);
            while (rs2Budget-- > 0 && !rs2Queue.isEmpty()) {
                Rs2Target target = rs2Queue.pollFirst();
                if (target == null) {
                    break;
                }
                scanDone++;
                scanPhase = PHASE_RUNTIME;
                int sent = AuditSnapshot.enqueueRs2Snapshot(target.network, target.storageId, itemFilter);
                if (sent > 0) {
                    lastScanned.put(target.storageId, now);
                }
                doneBySource.onlineRuntime++;
            }
        }

        reportScanProgress(now, false);
        if (!hasPendingTargets()) {
            scanRunning = false;
            scanPhase = scanReasonCode != null ? PHASE_DEGRADED : PHASE_COMPLETED;
            reportScanProgress(now, true);
            StructuredOpsLogger.info(
                "scan_session_finish",
                Map.of(
                    "trace_id", scanTraceId == null ? "" : scanTraceId,
                    "reason_code", scanReasonCode == null ? "" : scanReasonCode,
                    "done", scanDone,
                    "total", scanTotal
                )
            );
        }
        long elapsedMs = (System.nanoTime() - tickStartNs) / 1_000_000L;
        if (elapsedMs > 200) {
            Lattice.LOGGER.warn(
                "Scan tick slow: {}ms (inflight world={}, inflight offline={}, regionDirs={}, regionFiles={}, worldSnapshots={}, sbQueue={}, rs2Queue={})",
                elapsedMs,
                worldRegionInFlight,
                offlineDataInFlight,
                worldRegionDirQueue.size(),
                worldRegionFileQueue.size(),
                worldOfflineQueue.size(),
                sbOfflineQueue.size(),
                rs2OfflineQueue.size()
            );
        }
    }

    public MonitorScheduler.TaskProgressSnapshot getProgress() {
        return new MonitorScheduler.TaskProgressSnapshot(scanRunning, scanTotal, scanDone);
    }

    public void shutdown() {
        scanExecutor.shutdownNow();
        clearTargets();
    }

    private void startScan(MinecraftServer server, long now) {
        LatticeConfig snapshot = this.config;
        clearTargets();
        sourceTotals.reset();
        doneBySource.reset();
        scanReasonCode = null;
        scanReasonMessage = null;
        scanPhase = PHASE_INDEXING;
        scanTraceId = "scan-" + now + "-" + UUID.randomUUID();
        scanStartedAtMs = now;
        nextWorldProcessAtMs = now;

        boolean worldFailed = false;
        boolean sbFailed = false;
        boolean rs2Failed = false;

        if (snapshot.scanWorldOfflineEnabled) {
            try {
                enqueueWorldRegionDirs(server);
            } catch (Throwable e) {
                worldFailed = true;
                Lattice.LOGGER.warn("Offline world scan target build failed", e);
            }
        }

        if (snapshot.scanSbOfflineEnabled) {
            try {
                sourceTotals.sbOffline = enqueueSbOfflineTargets(server);
            } catch (Throwable e) {
                sbFailed = true;
                Lattice.LOGGER.warn("SB offline scan target build failed", e);
            }
        }

        if (snapshot.scanRs2OfflineEnabled) {
            try {
                sourceTotals.rs2Offline = enqueueRs2OfflineTargets(server);
            } catch (Throwable e) {
                rs2Failed = true;
                Lattice.LOGGER.warn("RS2 offline scan target build failed", e);
            }
        }

        if (snapshot.scanIncludeOnlineRuntime) {
            sourceTotals.onlineRuntime = enqueueOnlineRuntimeTargets(server, now);
        }

        scanTotal = sourceTotals.totalTargets() + worldRegionDirQueue.size();
        scanDone = 0;
        scanRunning = scanTotal > 0 || hasPendingTargets();

        if (!scanRunning) {
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
            scanPhase = PHASE_DEGRADED;
        }

        StructuredOpsLogger.info(
            "scan_session_start",
            Map.of(
                "trace_id", scanTraceId,
                "server_id", snapshot.serverId,
                "total", scanTotal,
                "world_targets", sourceTotals.worldContainers,
                "sb_targets", sourceTotals.sbOffline,
                "rs2_targets", sourceTotals.rs2Offline,
                "runtime_targets", sourceTotals.onlineRuntime
            )
        );
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

    private void processWorldOfflineTarget(WorldSnapshotTarget target, long now) {
        if (shouldSkip(target.storageId, now)) {
            return;
        }
        int sent = AuditSnapshot.enqueueStorageSnapshotCounts(
            target.counts,
            target.storageMod,
            target.storageId,
            target.dimension,
            target.pos
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

    private void enqueueWorldRegionDirs(MinecraftServer server) throws Exception {
        Path worldRoot = server.getWorldPath(LevelResource.ROOT);
        if (worldRoot == null) {
            throw new IllegalStateException("world root path unavailable");
        }

        for (ServerLevel level : server.getAllLevels()) {
            ResourceLocation dimId = level.dimension().location();
            Path dimPath = resolveDimensionPath(worldRoot, dimId);
            Path regionDir = dimPath.resolve("region");
            if (!Files.isDirectory(regionDir)) {
                continue;
            }
            worldRegionDirQueue.addLast(new RegionDirTarget(dimId, regionDir));
        }
    }

    private int enqueueSbOfflineTargets(MinecraftServer server) throws Exception {
        Path worldRoot = server.getWorldPath(LevelResource.ROOT);
        if (worldRoot == null || !Files.exists(worldRoot)) {
            throw new IllegalStateException("world root path unavailable");
        }
        Path sbDataFile = worldRoot.resolve("data").resolve("sophisticatedbackpacks.dat");
        if (!Files.isRegularFile(sbDataFile)) {
            return 0;
        }

        CompoundTag root = readCompressedTag(sbDataFile);
        if (root == null) {
            setReason(REASON_PARTIAL_COMPLETED, "SB 离线数据解析失败，已跳过");
            return 0;
        }
        CompoundTag payload = root.contains("data", Tag.TAG_COMPOUND) ? root.getCompound("data") : root;
        ListTag backpackContents = payload.getList("backpackContents", Tag.TAG_COMPOUND);
        if (backpackContents.isEmpty()) {
            return 0;
        }

        Map<UUID, CompoundTag> contentsByUuid = new HashMap<>(Math.max(16, backpackContents.size()));
        for (int i = 0; i < backpackContents.size(); i++) {
            Tag entryTag = backpackContents.get(i);
            if (!(entryTag instanceof CompoundTag pair)) {
                continue;
            }
            UUID uuid = readUuidFromCompound(pair, "uuid");
            if (uuid == null) {
                continue;
            }
            contentsByUuid.put(uuid, pair.getCompound("contents"));
        }
        if (contentsByUuid.isEmpty()) {
            return 0;
        }

        SbResolveStats stats = new SbResolveStats();
        Map<UUID, Map<String, Integer>> memo = new HashMap<>();
        int loaded = 0;
        for (UUID uuid : contentsByUuid.keySet()) {
            Map<String, Integer> counts = resolveSbBackpackCounts(
                uuid,
                contentsByUuid,
                memo,
                new HashSet<>(),
                1,
                stats
            );
            if (counts.isEmpty()) {
                continue;
            }
            String storageId = "sb-offline:" + uuid.toString().toLowerCase();
            sbOfflineQueue.addLast(new SbOfflineTarget(storageId, counts));
            loaded++;
        }

        if (stats.depthTruncations > 0 || stats.cycleDetections > 0) {
            setReason(REASON_PARTIAL_COMPLETED, "SB 离线递归解析存在截断或循环，已按可解析部分上报");
        }

        if (loaded > 0) {
            StructuredOpsLogger.info(
                "scan_sb_index_loaded",
                Map.of(
                    "trace_id", scanTraceId == null ? "" : scanTraceId,
                    "file", sbDataFile.toString(),
                    "targets", loaded,
                    "entries", contentsByUuid.size(),
                    "depth_truncations", stats.depthTruncations,
                    "cycle_detections", stats.cycleDetections
                )
            );
        }
        return loaded;
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
            worldRoot.resolve("playerdata")
        );
        Set<String> seenStorageIds = new HashSet<>();
        for (Path root : roots) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> stream = Files.walk(root, 6)) {
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

    private boolean submitNextWorldRegionDirectory() {
        if (worldRegionInFlight >= maxRegionInFlight) {
            return false;
        }
        RegionDirTarget nextDir = worldRegionDirQueue.pollFirst();
        if (nextDir == null) {
            return false;
        }
        try {
            scanPhase = PHASE_INDEXING;
            worldRegionCompletion.submit(() -> listRegionFiles(nextDir));
            worldRegionInFlight++;
            return true;
        } catch (Throwable error) {
            setReason(REASON_PARTIAL_COMPLETED, "世界区块目录任务提交失败");
            Lattice.LOGGER.warn("Submit region directory task failed: {}", nextDir.regionDir, error);
            scanDone++;
            return false;
        }
    }

    private boolean submitNextWorldRegionFile() {
        if (worldRegionInFlight >= maxRegionInFlight) {
            return false;
        }
        RegionFileTarget nextFile = worldRegionFileQueue.pollFirst();
        if (nextFile == null) {
            return false;
        }
        try {
            scanPhase = PHASE_OFFLINE_WORLD;
            worldRegionCompletion.submit(() -> scanRegionFile(nextFile.dimensionId, nextFile.regionFile));
            worldRegionInFlight++;
            return true;
        } catch (Throwable error) {
            setReason(REASON_PARTIAL_COMPLETED, "离线区块任务提交失败，已继续处理其他来源");
            Lattice.LOGGER.warn("Submit region scan task failed: {}", nextFile.regionFile, error);
            scanDone++;
            return false;
        }
    }

    private boolean collectRegionResult() {
        if (worldRegionInFlight <= 0) {
            return false;
        }
        Future<WorldTaskResult> future = worldRegionCompletion.poll();
        if (future == null) {
            return false;
        }
        worldRegionInFlight--;
        scanDone++;
        try {
            WorldTaskResult result = future.get();
            if (result.partialFailure) {
                setReason(REASON_PARTIAL_COMPLETED, "离线区块扫描部分失败，已继续处理可读数据");
            }
            if (!result.regionFiles.isEmpty()) {
                worldRegionFileQueue.addAll(result.regionFiles);
                scanTotal += result.regionFiles.size();
            }
            if (!result.snapshots.isEmpty()) {
                sourceTotals.worldContainers += result.snapshots.size();
                scanTotal += result.snapshots.size();
                int allowed = Math.max(0, WORLD_OFFLINE_QUEUE_LIMIT - worldOfflineQueue.size());
                if (allowed <= 0) {
                    setReason(REASON_PARTIAL_COMPLETED, "离线快照队列达到上限，已降速并丢弃超额任务");
                    StructuredOpsLogger.warn(
                        "scan_queue_backpressure",
                        Map.of("trace_id", scanTraceId == null ? "" : scanTraceId, "queue_size", worldOfflineQueue.size())
                    );
                } else if (result.snapshots.size() > allowed) {
                    worldOfflineQueue.addAll(result.snapshots.subList(0, allowed));
                    setReason(REASON_PARTIAL_COMPLETED, "离线快照队列拥塞，已丢弃部分任务");
                    StructuredOpsLogger.warn(
                        "scan_queue_partially_dropped",
                        Map.of(
                            "trace_id", scanTraceId == null ? "" : scanTraceId,
                            "queue_size", worldOfflineQueue.size(),
                            "dropped", result.snapshots.size() - allowed
                        )
                    );
                } else {
                    worldOfflineQueue.addAll(result.snapshots);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            setReason(REASON_PARTIAL_COMPLETED, "离线区块扫描线程中断");
            Lattice.LOGGER.warn("Collect region scan result interrupted", e);
        } catch (ExecutionException e) {
            setReason(REASON_PARTIAL_COMPLETED, "离线区块扫描执行失败");
            Lattice.LOGGER.warn("Collect region scan result failed", e.getCause());
        }
        return true;
    }

    private WorldTaskResult listRegionFiles(RegionDirTarget target) {
        List<RegionFileTarget> files = new ArrayList<>();
        boolean failed = false;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(target.regionDir, "r.*.*.mca")) {
            for (Path regionFile : stream) {
                files.add(new RegionFileTarget(target.dimensionId, regionFile));
            }
        } catch (Throwable error) {
            failed = true;
            Lattice.LOGGER.warn("List region files failed: {}", target.regionDir, error);
        }
        return WorldTaskResult.regionFiles(files, failed);
    }

    private WorldTaskResult scanRegionFile(ResourceLocation dimensionId, Path regionFile) {
        Matcher matcher = REGION_FILE_PATTERN.matcher(regionFile.getFileName().toString());
        if (!matcher.matches()) {
            return WorldTaskResult.empty();
        }
        int regionX;
        int regionZ;
        try {
            regionX = Integer.parseInt(matcher.group(1));
            regionZ = Integer.parseInt(matcher.group(2));
        } catch (Exception parseError) {
            Lattice.LOGGER.debug("Parse region file name failed: {}", regionFile, parseError);
            return WorldTaskResult.failed();
        }

        List<WorldSnapshotTarget> snapshots = new ArrayList<>();
        boolean failed = false;
        try (FileChannel channel = FileChannel.open(regionFile, StandardOpenOption.READ)) {
            byte[] header = readRegionHeader(channel);
            if (header.length < 4096) {
                return WorldTaskResult.failed();
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
                CompoundTag chunkTag = readChunkTag(
                    channel,
                    regionFile,
                    regionX,
                    regionZ,
                    localX,
                    localZ,
                    offset,
                    sectors
                );
                if (chunkTag == null) {
                    continue;
                }
                snapshots.addAll(extractChunkSnapshots(dimensionId.toString(), chunkTag));
            }
        } catch (Throwable error) {
            failed = true;
            Lattice.LOGGER.warn("Scan region file failed: {}", regionFile, error);
        }
        return WorldTaskResult.snapshots(snapshots, failed);
    }

    private byte[] readRegionHeader(FileChannel channel) {
        try {
            ByteBuffer header = ByteBuffer.allocate(4096);
            if (!readFully(channel, 0, header)) {
                return new byte[0];
            }
            return header.array();
        } catch (IOException error) {
            Lattice.LOGGER.debug("Read region header failed", error);
            return new byte[0];
        }
    }

    private CompoundTag readChunkTag(
        FileChannel channel,
        Path regionFile,
        int regionX,
        int regionZ,
        int localX,
        int localZ,
        int sectorOffset,
        int sectors
    ) {
        try {
            long chunkOffset = sectorOffset * 4096L;
            ByteBuffer prefix = ByteBuffer.allocate(5);
            if (!readFully(channel, chunkOffset, prefix)) {
                return null;
            }
            prefix.flip();
            int storedLength = prefix.getInt();
            int compressionByte = prefix.get() & 0xFF;
            if (storedLength <= 1) {
                return null;
            }

            int compressionType = compressionByte & 0x7F;
            boolean external = (compressionByte & 0x80) != 0;
            byte[] payload;

            if (external) {
                int chunkX = regionX * 32 + localX;
                int chunkZ = regionZ * 32 + localZ;
                Path externalFile = regionFile.getParent().resolve("c." + chunkX + "." + chunkZ + ".mcc");
                if (!Files.isRegularFile(externalFile)) {
                    return null;
                }
                payload = Files.readAllBytes(externalFile);
            } else {
                int payloadLength = Math.min(storedLength - 1, sectors * 4096 - 1);
                if (payloadLength <= 0) {
                    return null;
                }
                ByteBuffer payloadBuffer = ByteBuffer.allocate(payloadLength);
                if (!readFully(channel, chunkOffset + 5, payloadBuffer)) {
                    return null;
                }
                payloadBuffer.flip();
                payload = new byte[payloadBuffer.remaining()];
                payloadBuffer.get(payload);
            }

            return decodeChunkTag(payload, compressionType);
        } catch (Throwable error) {
            Lattice.LOGGER.debug("Read chunk tag failed in {}", regionFile, error);
            return null;
        }
    }

    private boolean readFully(FileChannel channel, long position, ByteBuffer target) throws IOException {
        while (target.hasRemaining()) {
            int read = channel.read(target, position);
            if (read <= 0) {
                return false;
            }
            position += read;
        }
        return true;
    }

    private CompoundTag decodeChunkTag(byte[] payload, int compressionType) {
        try {
            InputStream compressed = new ByteArrayInputStream(payload);
            InputStream decoded = switch (compressionType) {
                case 1 -> new GZIPInputStream(compressed);
                case 2 -> new InflaterInputStream(compressed);
                case 3 -> compressed;
                default -> null;
            };
            if (decoded == null) {
                return null;
            }
            byte[] nbtBytes;
            try (InputStream input = decoded) {
                nbtBytes = input.readAllBytes();
            }
            return readUncompressedTag(nbtBytes);
        } catch (Throwable error) {
            Lattice.LOGGER.debug("Decode chunk tag failed", error);
            return null;
        }
    }

    private CompoundTag readUncompressedTag(byte[] data) {
        Object accounter = null;
        try {
            Method unlimited = NbtAccounter.class.getMethod("unlimitedHeap");
            accounter = unlimited.invoke(null);
        } catch (Exception ignored) {
            // try methods without accounter
        }

        if (accounter != null) {
            Object value = invokeStatic(
                NbtIo.class,
                "read",
                new Class<?>[]{InputStream.class, NbtAccounter.class},
                new Object[]{new ByteArrayInputStream(data), accounter}
            );
            if (value instanceof CompoundTag tag) {
                return tag;
            }
        }

        Object value = invokeStatic(
            NbtIo.class,
            "read",
            new Class<?>[]{InputStream.class},
            new Object[]{new ByteArrayInputStream(data)}
        );
        if (value instanceof CompoundTag tag) {
            return tag;
        }

        if (accounter != null) {
            value = invokeStatic(
                NbtIo.class,
                "read",
                new Class<?>[]{DataInput.class, NbtAccounter.class},
                new Object[]{new DataInputStream(new ByteArrayInputStream(data)), accounter}
            );
            if (value instanceof CompoundTag tag) {
                return tag;
            }
        }

        value = invokeStatic(
            NbtIo.class,
            "read",
            new Class<?>[]{DataInput.class},
            new Object[]{new DataInputStream(new ByteArrayInputStream(data))}
        );
        if (value instanceof CompoundTag tag) {
            return tag;
        }
        return null;
    }

    private List<WorldSnapshotTarget> extractChunkSnapshots(String dimension, CompoundTag chunkTag) {
        CompoundTag payload = chunkTag;
        if (chunkTag.contains("Level", Tag.TAG_COMPOUND)) {
            payload = chunkTag.getCompound("Level");
        }

        List<WorldSnapshotTarget> results = new ArrayList<>();
        ListTag blockEntities = payload.getList("block_entities", Tag.TAG_COMPOUND);
        if (blockEntities.size() == 0) {
            blockEntities = payload.getList("TileEntities", Tag.TAG_COMPOUND);
        }
        for (int i = 0; i < blockEntities.size(); i++) {
            Tag tag = blockEntities.get(i);
            if (!(tag instanceof CompoundTag blockEntityTag)) {
                continue;
            }
            if (!blockEntityTag.contains("x", Tag.TAG_INT)
                || !blockEntityTag.contains("y", Tag.TAG_INT)
                || !blockEntityTag.contains("z", Tag.TAG_INT)) {
                continue;
            }
            String blockEntityId = blockEntityTag.getString("id");
            int x = blockEntityTag.getInt("x");
            int y = blockEntityTag.getInt("y");
            int z = blockEntityTag.getInt("z");
            BlockPos pos = new BlockPos(x, y, z);
            Map<String, Integer> counts = new HashMap<>();
            collectNbtItems(blockEntityTag, counts);
            if (counts.isEmpty()) {
                continue;
            }
            String storageMod = namespaceOf(blockEntityId);
            String storageId = storageMod + ":" + dimension + ":" + x + "," + y + "," + z;
            results.add(new WorldSnapshotTarget(counts, storageMod, storageId, dimension, pos));
        }
        return results;
    }

    private String namespaceOf(String id) {
        if (id == null || id.isBlank()) {
            return "unknown";
        }
        int idx = id.indexOf(':');
        if (idx <= 0) {
            return "minecraft";
        }
        return id.substring(0, idx).toLowerCase();
    }

    private boolean submitNextOfflineDataTask(long now) {
        boolean preferRs2 = rotateOfflineSource;
        rotateOfflineSource = !rotateOfflineSource;

        if (preferRs2) {
            if (submitNextRs2OfflineTask(now)) {
                return true;
            }
            return processNextSbOfflineTarget(now);
        }
        if (processNextSbOfflineTarget(now)) {
            return true;
        }
        return submitNextRs2OfflineTask(now);
    }

    private boolean processNextSbOfflineTarget(long now) {
        SbOfflineTarget target = sbOfflineQueue.pollFirst();
        if (target == null) {
            return false;
        }
        scanDone++;
        scanPhase = PHASE_OFFLINE_SB;
        if (shouldSkip(target.storageId, now)) {
            return true;
        }

        int sent = AuditSnapshot.enqueueStorageSnapshotCounts(
            target.counts,
            "sophisticatedbackpacks",
            target.storageId,
            null,
            null
        );
        if (sent > 0) {
            lastScanned.put(target.storageId, now);
        }
        doneBySource.sbOffline++;
        return true;
    }

    private boolean submitNextRs2OfflineTask(long now) {
        if (offlineDataInFlight >= maxRegionInFlight) {
            return false;
        }
        OfflineDataTarget target = rs2OfflineQueue.pollFirst();
        if (target == null) {
            return false;
        }
        if (shouldSkip(target.storageId, now)) {
            scanDone++;
            return true;
        }
        scanPhase = PHASE_OFFLINE_RS2;
        try {
            offlineDataCompletion.submit(() -> parseOfflineDataTask(target));
            offlineDataInFlight++;
            return true;
        } catch (Throwable error) {
            setReason(REASON_PARTIAL_COMPLETED, "离线存储任务提交失败，已跳过部分目标");
            Lattice.LOGGER.warn("Submit offline data task failed: {}", target.path, error);
            scanDone++;
            return false;
        }
    }

    private boolean collectOfflineDataResult(long now) {
        if (offlineDataInFlight <= 0) {
            return false;
        }
        Future<OfflineDataTaskResult> future = offlineDataCompletion.poll();
        if (future == null) {
            return false;
        }
        offlineDataInFlight--;
        scanDone++;
        try {
            OfflineDataTaskResult result = future.get();
            if (result.partialFailure) {
                if ("rs2".equals(result.storageMod)) {
                    setReason(REASON_PARTIAL_COMPLETED, "RS2 离线数据部分解析失败");
                } else {
                    setReason(REASON_PARTIAL_COMPLETED, "离线存储数据部分解析失败");
                }
            }
            if (result.counts.isEmpty()) {
                return true;
            }
            int sent = AuditSnapshot.enqueueStorageSnapshotCounts(
                result.counts,
                result.storageMod,
                result.storageId,
                null,
                null
            );
            if (sent > 0) {
                lastScanned.put(result.storageId, now);
            }
            if ("rs2".equals(result.storageMod)) {
                doneBySource.rs2Offline++;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            setReason(REASON_PARTIAL_COMPLETED, "离线存储扫描线程中断");
            Lattice.LOGGER.warn("Collect offline data result interrupted", e);
        } catch (ExecutionException e) {
            setReason(REASON_PARTIAL_COMPLETED, "离线存储扫描执行失败");
            Lattice.LOGGER.warn("Collect offline data result failed", e.getCause());
        }
        return true;
    }

    private OfflineDataTaskResult parseOfflineDataTask(OfflineDataTarget target) {
        try {
            Map<String, Integer> counts = readNbtItemCounts(target.path);
            return new OfflineDataTaskResult(target.storageMod, target.storageId, counts, false);
        } catch (Exception e) {
            Lattice.LOGGER.debug("Offline storage parse failed: {}", target.path, e);
            return new OfflineDataTaskResult(target.storageMod, target.storageId, Map.of(), true);
        }
    }

    private boolean hasPendingTargets() {
        return !containerQueue.isEmpty()
            || !rs2Queue.isEmpty()
            || !worldRegionDirQueue.isEmpty()
            || !worldRegionFileQueue.isEmpty()
            || worldRegionInFlight > 0
            || !worldOfflineQueue.isEmpty()
            || !sbOfflineQueue.isEmpty()
            || !rs2OfflineQueue.isEmpty()
            || offlineDataInFlight > 0;
    }

    private void clearTargets() {
        containerQueue.clear();
        rs2Queue.clear();
        worldRegionDirQueue.clear();
        worldRegionFileQueue.clear();
        worldOfflineQueue.clear();
        sbOfflineQueue.clear();
        rs2OfflineQueue.clear();
        worldRegionInFlight = 0;
        offlineDataInFlight = 0;
        rotateOfflineSource = false;
        nextWorldProcessAtMs = 0L;
    }

    private void reportScanProgress(long now, boolean force) {
        if (!force) {
            if (now - scanLastReportMs < 2000 && scanDone - scanLastReportedDone < 20) {
                return;
            }
        }
        scanLastReportMs = now;
        scanLastReportedDone = scanDone;
        double throughput = 0.0D;
        if (scanStartedAtMs > 0 && now > scanStartedAtMs) {
            throughput = (double) scanDone / ((double) (now - scanStartedAtMs) / 1000.0D);
        }
        TaskProgressReporter.report(
            config,
            "scan",
            scanRunning,
            scanTotal,
            scanDone,
            scanReasonCode,
            scanReasonMessage,
            sourceTotals.toPayload(),
            scanPhase,
            doneBySource.toPayload(),
            scanTraceId,
            throughput
        );
        StructuredOpsLogger.debug(
            "scan_stage_progress",
            Map.of(
                "trace_id", scanTraceId == null ? "" : scanTraceId,
                "phase", scanPhase == null ? "" : scanPhase,
                "done", scanDone,
                "total", scanTotal,
                "throughput_per_sec", throughput
            )
        );
    }

    private void setReason(String code, String message) {
        this.scanReasonCode = code;
        this.scanReasonMessage = message;
        if (REASON_PARTIAL_COMPLETED.equals(code)) {
            this.scanPhase = PHASE_DEGRADED;
        }
    }

    private boolean shouldSkip(String storageId, long now) {
        long cooldownMs = this.config.scanRescanCooldownMinutes * 60_000L;
        if (cooldownMs <= 0) {
            return false;
        }
        Long last = lastScanned.get(storageId);
        return last != null && now - last < cooldownMs;
    }

    private boolean isServerHealthy(MinecraftServer server, LatticeConfig snapshot) {
        if (snapshot.scanMaxOnlinePlayers >= 0) {
            int online = server.getPlayerList().getPlayerCount();
            if (online > snapshot.scanMaxOnlinePlayers) {
                return false;
            }
        }
        if (snapshot.scanMaxAvgTickMs > 0) {
            Double avg = readAverageTickMs(server);
            if (avg != null && avg > snapshot.scanMaxAvgTickMs) {
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

    private Map<String, Integer> readNbtItemCounts(Path file) {
        try {
            CompoundTag root = readCompressedTag(file);
            if (root == null) {
                return Map.of();
            }
            Map<String, Integer> counts = new HashMap<>();
            collectNbtItems(root, counts);
            return counts;
        } catch (Exception e) {
            Lattice.LOGGER.debug("Offline storage parse failed: {}", file, e);
            return Map.of();
        }
    }

    private Map<String, Integer> resolveSbBackpackCounts(
        UUID uuid,
        Map<UUID, CompoundTag> contentsByUuid,
        Map<UUID, Map<String, Integer>> memo,
        Set<UUID> recursionGuard,
        int depth,
        SbResolveStats stats
    ) {
        if (uuid == null) {
            return Map.of();
        }
        Map<String, Integer> cached = memo.get(uuid);
        if (cached != null) {
            return cached;
        }
        if (depth > MAX_NBT_RECURSION_DEPTH) {
            stats.depthTruncations++;
            return Map.of();
        }
        if (!recursionGuard.add(uuid)) {
            stats.cycleDetections++;
            return Map.of();
        }

        CompoundTag contents = contentsByUuid.get(uuid);
        if (contents == null || contents.isEmpty()) {
            recursionGuard.remove(uuid);
            memo.put(uuid, Map.of());
            return Map.of();
        }

        Map<String, Integer> counts = new HashMap<>();
        collectNbtItems(contents, counts);

        Set<UUID> nestedUuids = new HashSet<>();
        collectNestedSbBackpackUuids(contents, nestedUuids, 0);
        if (depth >= MAX_NBT_RECURSION_DEPTH && !nestedUuids.isEmpty()) {
            stats.depthTruncations++;
        } else if (depth < MAX_NBT_RECURSION_DEPTH) {
            for (UUID nestedUuid : nestedUuids) {
                if (nestedUuid == null || nestedUuid.equals(uuid)) {
                    continue;
                }
                Map<String, Integer> nestedCounts = resolveSbBackpackCounts(
                    nestedUuid,
                    contentsByUuid,
                    memo,
                    recursionGuard,
                    depth + 1,
                    stats
                );
                mergeCounts(counts, nestedCounts);
            }
        }

        recursionGuard.remove(uuid);
        memo.put(uuid, counts);
        return counts;
    }

    private void collectNestedSbBackpackUuids(Tag tag, Set<UUID> backpackUuids, int depth) {
        if (tag == null || depth > MAX_NBT_RECURSION_DEPTH) {
            return;
        }
        if (tag instanceof CompoundTag compound) {
            maybeCollectSbBackpackUuidFromStackTag(compound, backpackUuids);
            for (String key : compound.getAllKeys()) {
                collectNestedSbBackpackUuids(compound.get(key), backpackUuids, depth + 1);
            }
            return;
        }
        if (tag instanceof ListTag list) {
            for (int i = 0; i < list.size(); i++) {
                collectNestedSbBackpackUuids(list.get(i), backpackUuids, depth + 1);
            }
        }
    }

    private void maybeCollectSbBackpackUuidFromStackTag(CompoundTag stackTag, Set<UUID> backpackUuids) {
        String itemId = stackTag.getString("id");
        if (!isSophisticatedBackpackItemId(itemId)) {
            return;
        }
        UUID uuid = extractSbStorageUuidFromStackTag(stackTag);
        if (uuid != null) {
            backpackUuids.add(uuid);
        }
    }

    private boolean isSophisticatedBackpackItemId(String itemId) {
        if (itemId == null || itemId.isBlank()) {
            return false;
        }
        ResourceLocation key = ResourceLocation.tryParse(itemId);
        if (key == null) {
            return false;
        }
        String namespace = key.getNamespace().toLowerCase();
        String path = key.getPath().toLowerCase();
        if ("sophisticatedbackpacks".equals(namespace)) {
            return true;
        }
        return namespace.contains("sophisticated") && path.contains("backpack");
    }

    private UUID extractSbStorageUuidFromStackTag(CompoundTag stackTag) {
        UUID fromComponents = extractSbStorageUuidFromComponents(stackTag.getCompound("components"));
        if (fromComponents != null) {
            return fromComponents;
        }
        CompoundTag legacyTag = stackTag.getCompound("tag");
        if (!legacyTag.isEmpty()) {
            UUID fromLegacy = findUuidByHint(legacyTag, 0);
            if (fromLegacy != null) {
                return fromLegacy;
            }
        }
        return findUuidByHint(stackTag, 0);
    }

    private UUID extractSbStorageUuidFromComponents(CompoundTag componentsTag) {
        if (componentsTag == null || componentsTag.isEmpty()) {
            return null;
        }
        UUID direct = readUuidFromCompound(componentsTag, "sophisticatedcore:storage_uuid");
        if (direct != null) {
            return direct;
        }
        for (String key : componentsTag.getAllKeys()) {
            String lower = key.toLowerCase();
            if (!lower.contains("storage_uuid") && !lower.contains("storageuuid")) {
                continue;
            }
            UUID uuid = readUuidFromTag(componentsTag.get(key));
            if (uuid != null) {
                return uuid;
            }
        }
        return null;
    }

    private UUID findUuidByHint(Tag tag, int depth) {
        if (tag == null || depth > MAX_NBT_RECURSION_DEPTH) {
            return null;
        }
        if (tag instanceof CompoundTag compound) {
            for (String key : compound.getAllKeys()) {
                String lower = key.toLowerCase();
                if (lower.contains("storage_uuid") || lower.contains("storageuuid") || lower.endsWith("uuid")) {
                    UUID uuid = readUuidFromTag(compound.get(key));
                    if (uuid != null) {
                        return uuid;
                    }
                }
            }
            for (String key : compound.getAllKeys()) {
                UUID nested = findUuidByHint(compound.get(key), depth + 1);
                if (nested != null) {
                    return nested;
                }
            }
            return null;
        }
        if (tag instanceof ListTag list) {
            for (int i = 0; i < list.size(); i++) {
                UUID nested = findUuidByHint(list.get(i), depth + 1);
                if (nested != null) {
                    return nested;
                }
            }
            return null;
        }
        return readUuidFromTag(tag);
    }

    private UUID readUuidFromCompound(CompoundTag compound, String key) {
        if (compound == null || key == null || key.isBlank()) {
            return null;
        }
        try {
            if (compound.hasUUID(key)) {
                return compound.getUUID(key);
            }
        } catch (Throwable ignored) {
            // ignore direct UUID lookup failures
        }
        return readUuidFromTag(compound.get(key));
    }

    private UUID readUuidFromTag(Tag tag) {
        if (tag == null) {
            return null;
        }
        if (tag instanceof IntArrayTag intArrayTag) {
            return uuidFromIntArray(intArrayTag.getAsIntArray());
        }
        if (tag instanceof CompoundTag compound) {
            if (compound.contains("most", Tag.TAG_LONG) && compound.contains("least", Tag.TAG_LONG)) {
                return new UUID(compound.getLong("most"), compound.getLong("least"));
            }
            if (compound.contains("Most", Tag.TAG_LONG) && compound.contains("Least", Tag.TAG_LONG)) {
                return new UUID(compound.getLong("Most"), compound.getLong("Least"));
            }
            if (compound.contains("M", Tag.TAG_LONG) && compound.contains("L", Tag.TAG_LONG)) {
                return new UUID(compound.getLong("M"), compound.getLong("L"));
            }
            for (String key : compound.getAllKeys()) {
                UUID nested = readUuidFromTag(compound.get(key));
                if (nested != null) {
                    return nested;
                }
            }
            return null;
        }
        if (tag instanceof ListTag listTag && listTag.size() == 4) {
            int[] value = new int[4];
            for (int i = 0; i < 4; i++) {
                try {
                    value[i] = Integer.parseInt(listTag.get(i).getAsString());
                } catch (Exception ignored) {
                    return null;
                }
            }
            return uuidFromIntArray(value);
        }
        try {
            return UUID.fromString(tag.getAsString());
        } catch (Exception ignored) {
            return null;
        }
    }

    private UUID uuidFromIntArray(int[] value) {
        if (value == null || value.length != 4) {
            return null;
        }
        long most = ((long) value[0] << 32) | (value[1] & 0xFFFF_FFFFL);
        long least = ((long) value[2] << 32) | (value[3] & 0xFFFF_FFFFL);
        return new UUID(most, least);
    }

    private void mergeCounts(Map<String, Integer> base, Map<String, Integer> extra) {
        if (extra == null || extra.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Integer> entry : extra.entrySet()) {
            int value = entry.getValue() == null ? 0 : entry.getValue();
            if (value <= 0) {
                continue;
            }
            base.merge(entry.getKey(), value, (a, b) -> {
                long sum = (long) a + b;
                return sum > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) sum;
            });
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
        collectNbtItems(tag, counts, 0);
    }

    private void collectNbtItems(Tag tag, Map<String, Integer> counts, int depth) {
        if (tag == null) {
            return;
        }
        if (depth > MAX_NBT_RECURSION_DEPTH) {
            setReason(REASON_PARTIAL_COMPLETED, "检测到深层嵌套容器，已按最大递归深度截断");
            return;
        }

        if (tag instanceof CompoundTag compound) {
            collectItemIfStackLike(compound, counts);
            for (String key : compound.getAllKeys()) {
                collectNbtItems(compound.get(key), counts, depth + 1);
            }
            return;
        }

        if (tag instanceof ListTag list) {
            for (int i = 0; i < list.size(); i++) {
                collectNbtItems(list.get(i), counts, depth + 1);
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

    private record RegionDirTarget(ResourceLocation dimensionId, Path regionDir) {
    }

    private record RegionFileTarget(ResourceLocation dimensionId, Path regionFile) {
    }

    private record WorldSnapshotTarget(
        Map<String, Integer> counts,
        String storageMod,
        String storageId,
        String dimension,
        BlockPos pos
    ) {
    }

    private record SbOfflineTarget(String storageId, Map<String, Integer> counts) {
    }

    private record OfflineDataTarget(Path path, String storageMod, String storageId) {
    }

    private record OfflineDataTaskResult(
        String storageMod,
        String storageId,
        Map<String, Integer> counts,
        boolean partialFailure
    ) {
    }

    private record WorldTaskResult(
        List<RegionFileTarget> regionFiles,
        List<WorldSnapshotTarget> snapshots,
        boolean partialFailure
    ) {
        static WorldTaskResult empty() {
            return new WorldTaskResult(List.of(), List.of(), false);
        }

        static WorldTaskResult failed() {
            return new WorldTaskResult(List.of(), List.of(), true);
        }

        static WorldTaskResult regionFiles(List<RegionFileTarget> regionFiles, boolean partialFailure) {
            return new WorldTaskResult(regionFiles, List.of(), partialFailure);
        }

        static WorldTaskResult snapshots(List<WorldSnapshotTarget> snapshots, boolean partialFailure) {
            return new WorldTaskResult(List.of(), snapshots, partialFailure);
        }
    }

    private static final class ScanWorkerFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "lattice-scan-worker-" + SCAN_WORKER_ID.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        }
    }

    private static final class SbResolveStats {
        int depthTruncations;
        int cycleDetections;
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

    private static final class DoneBySource {
        int worldContainers;
        int sbOffline;
        int rs2Offline;
        int onlineRuntime;

        TaskProgressReporter.DoneBySourcePayload toPayload() {
            return new TaskProgressReporter.DoneBySourcePayload(
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
