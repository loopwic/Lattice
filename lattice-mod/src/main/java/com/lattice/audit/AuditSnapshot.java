package com.lattice.audit;

import com.lattice.Lattice;
import com.lattice.events.EventPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import java.io.InputStream;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class AuditSnapshot {
    private static final int MAX_NESTED_DEPTH = 8;
    private static final int MAX_OBJECT_TRAVERSAL_PER_STACK = 4096;
    private static final Pattern UUID_PATTERN = Pattern.compile(
        "(?i)\\b[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\\b"
    );
    private static final long SB_CACHE_REFRESH_MS = 15_000L;
    private static final Map<Path, SbStorageIndexCache> SB_STORAGE_INDEX_CACHE = new ConcurrentHashMap<>();
    private static volatile boolean SB_STORAGE_UUID_COMPONENT_RESOLVED = false;
    private static volatile DataComponentType<?> SB_STORAGE_UUID_COMPONENT = null;

    private AuditSnapshot() {
    }

    public static int enqueuePlayerSnapshot(ServerPlayer player, Set<String> itemFilter) {
        String snapshotId = UUID.randomUUID().toString();
        Map<String, Integer> counts = new HashMap<>();
        collectCounts(player.getInventory(), counts, itemFilter);
        collectCounts(player.getEnderChestInventory(), counts, itemFilter);
        mergeSbBackpackCounts(player, counts, itemFilter);
        return emitSnapshotEvents(
            counts,
            snapshotId,
            "INVENTORY_SNAPSHOT",
            "inventory_snapshot",
            player.level().dimension().location().toString(),
            player.blockPosition(),
            player.getGameProfile().getId().toString(),
            player.getGameProfile().getName(),
            null,
            null,
            "player"
        );
    }

    public static int enqueueContainerSnapshot(
        ServerLevel level,
        BlockPos pos,
        Container container,
        String storageMod,
        String storageId,
        Set<String> itemFilter
    ) {
        String snapshotId = UUID.randomUUID().toString();
        Map<String, Integer> counts = new HashMap<>();
        collectCounts(container, counts, itemFilter);
        return emitSnapshotEvents(
            counts,
            snapshotId,
            "STORAGE_SNAPSHOT",
            "storage_snapshot",
            level.dimension().location().toString(),
            pos,
            "",
            "",
            storageMod,
            storageId,
            "system"
        );
    }

    public static int enqueueRs2Snapshot(Object network, String storageId, Set<String> itemFilter) {
        if (network == null) {
            return 0;
        }
        Map<String, Integer> counts = Rs2SnapshotReader.readNetworkItems(network, itemFilter);
        if (counts.isEmpty()) {
            return 0;
        }
        String snapshotId = UUID.randomUUID().toString();
        return emitSnapshotEvents(
            counts,
            snapshotId,
            "STORAGE_SNAPSHOT",
            "storage_snapshot",
            null,
            null,
            "",
            "",
            "rs2",
            storageId,
            "system"
        );
    }

    public static int enqueueStorageSnapshotCounts(
        Map<String, Integer> counts,
        String storageMod,
        String storageId,
        String dim,
        BlockPos pos
    ) {
        if (counts == null || counts.isEmpty()) {
            return 0;
        }
        String snapshotId = UUID.randomUUID().toString();
        return emitSnapshotEvents(
            counts,
            snapshotId,
            "STORAGE_SNAPSHOT",
            "storage_snapshot",
            dim,
            pos,
            "",
            "",
            storageMod,
            storageId,
            "system"
        );
    }

    private static int emitSnapshotEvents(
        Map<String, Integer> counts,
        String snapshotId,
        String eventType,
        String originType,
        String dim,
        BlockPos pos,
        String playerUuid,          
        String playerName,
        String storageMod,
        String storageId,
        String actorType
    ) {
        if (counts.isEmpty()) {
            return 0;
        }
        int sent = 0;
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            int count = entry.getValue();
            if (count <= 0) {
                continue;
            }
            EventPayload payload = new EventPayload();
            payload.event_id = UUID.randomUUID().toString();
            payload.event_time = System.currentTimeMillis();
            payload.server_id = Lattice.getConfig().serverId;
            payload.event_type = eventType;
            payload.player_uuid = playerUuid;
            payload.player_name = playerName;
            payload.item_id = entry.getKey();
            payload.count = count;
            payload.origin_type = originType;
            payload.origin_ref = originType;
            payload.source_type = originType;
            payload.source_ref = "";
            payload.storage_mod = storageMod;
            payload.storage_id = storageId;
            payload.actor_type = actorType;
            payload.trace_id = snapshotId;
            payload.item_fingerprint = entry.getKey() + ":snapshot:" + snapshotId;
            payload.dim = dim;
            if (pos != null) {
                payload.x = pos.getX();
                payload.y = pos.getY();
                payload.z = pos.getZ();
            }
            Lattice.getEventQueue().enqueue(payload);
            sent++;
        }
        return sent;
    }

    private static void collectCounts(Container container, Map<String, Integer> counts, Set<String> itemFilter) {
        if (container == null) {
            return;
        }
        int size = container.getContainerSize();
        for (int i = 0; i < size; i++) {
            ItemStack stack = container.getItem(i);
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            collectStackAndNested(stack, counts, itemFilter, 1, 0);
        }
    }

    private static void mergeSbBackpackCounts(ServerPlayer player, Map<String, Integer> counts, Set<String> itemFilter) {
        if (player == null) {
            return;
        }
        if (!Lattice.getConfig().scanSbOfflineEnabled) {
            return;
        }
        Path worldRoot = null;
        try {
            if (player.getServer() != null) {
                worldRoot = player.getServer().getWorldPath(LevelResource.ROOT);
            }
        } catch (Exception ignored) {
            // ignore
        }
        if (worldRoot == null || !Files.isDirectory(worldRoot)) {
            return;
        }

        Set<UUID> backpackUuids = new HashSet<>();
        collectSbBackpackUuids(player.getInventory(), backpackUuids);
        collectSbBackpackUuids(player.getEnderChestInventory(), backpackUuids);
        if (backpackUuids.isEmpty()) {
            Lattice.LOGGER.debug(
                "Player audit no SB storage UUID found for player {} ({})",
                player.getGameProfile().getName(),
                player.getUUID()
            );
            return;
        }

        Path sbDataFile = worldRoot.resolve("data").resolve("sophisticatedbackpacks.dat");
        if (!Files.isRegularFile(sbDataFile)) {
            Lattice.LOGGER.debug(
                "Player audit SB data file not found: {} for player {} ({})",
                sbDataFile,
                player.getGameProfile().getName(),
                player.getUUID()
            );
            return;
        }

        SbStorageIndex storageIndex = loadSbStorageIndex(sbDataFile);
        if (storageIndex.contentsByUuid.isEmpty()) {
            Lattice.LOGGER.debug(
                "Player audit SB storage index empty for player {} ({}) file {}",
                player.getGameProfile().getName(),
                player.getUUID(),
                sbDataFile
            );
            return;
        }

        Deque<UuidDepth> queue = new ArrayDeque<>();
        for (UUID uuid : backpackUuids) {
            queue.addLast(new UuidDepth(uuid, 1));
        }

        Set<UUID> visited = new HashSet<>();
        int matchedBackpacks = 0;
        int discoveredNested = 0;
        long mergedItemCount = 0L;

        while (!queue.isEmpty()) {
            UuidDepth current = queue.pollFirst();
            if (current.depth > MAX_NESTED_DEPTH || !visited.add(current.uuid)) {
                continue;
            }

            CompoundTag contentsTag = storageIndex.contentsByUuid.get(current.uuid);
            if (contentsTag == null || contentsTag.isEmpty()) {
                continue;
            }

            matchedBackpacks++;
            long beforeTotal = totalCount(counts);
            collectNestedFromTag(contentsTag, counts, itemFilter, 1, 0);
            long afterTotal = totalCount(counts);
            if (afterTotal > beforeTotal) {
                mergedItemCount += (afterTotal - beforeTotal);
            }

            if (current.depth >= MAX_NESTED_DEPTH) {
                continue;
            }

            Set<UUID> nestedUuids = new HashSet<>();
            collectNestedSbBackpackUuids(contentsTag, nestedUuids, 0);
            for (UUID nestedUuid : nestedUuids) {
                if (visited.contains(nestedUuid)) {
                    continue;
                }
                queue.addLast(new UuidDepth(nestedUuid, current.depth + 1));
                discoveredNested++;
            }
        }

        if (matchedBackpacks <= 0) {
            Lattice.LOGGER.debug(
                "Player audit SB storage UUIDs found but no backpackContents matched for player {} ({}) uuids={} indexSize={}",
                player.getGameProfile().getName(),
                player.getUUID(),
                backpackUuids.size(),
                storageIndex.contentsByUuid.size()
            );
        } else {
            Lattice.LOGGER.debug(
                "Player audit SB merge complete player {} ({}) rootUuids={} matched={} nestedDiscovered={} mergedItems={} indexSize={}",
                player.getGameProfile().getName(),
                player.getUUID(),
                backpackUuids.size(),
                matchedBackpacks,
                discoveredNested,
                mergedItemCount,
                storageIndex.contentsByUuid.size()
            );
        }
    }

    private static long totalCount(Map<String, Integer> counts) {
        long total = 0L;
        for (int value : counts.values()) {
            if (value > 0) {
                total += value;
            }
        }
        return total;
    }

    private static void collectNestedSbBackpackUuids(Tag tag, Set<UUID> backpackUuids, int depth) {
        if (tag == null || depth > MAX_NESTED_DEPTH) {
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

    private static void maybeCollectSbBackpackUuidFromStackTag(CompoundTag stackTag, Set<UUID> backpackUuids) {
        String itemId = stackTag.getString("id");
        if (!isSophisticatedBackpackItemId(itemId)) {
            return;
        }
        UUID uuid = extractSbStorageUuidFromStackTag(stackTag);
        if (uuid != null) {
            backpackUuids.add(uuid);
        }
    }

    private static UUID extractSbStorageUuidFromStackTag(CompoundTag stackTag) {
        return extractSbStorageUuidFromComponents(stackTag.getCompound("components"));
    }

    private static UUID extractSbStorageUuidFromComponents(CompoundTag componentsTag) {
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
            UUID uuid = readUuidFromTag(componentsTag.get(key), key, 0);
            if (uuid != null) {
                return uuid;
            }
        }
        return null;
    }

    private static void collectSbBackpackUuids(Container container, Set<UUID> backpackUuids) {
        if (container == null) {
            return;
        }
        int size = container.getContainerSize();
        for (int i = 0; i < size; i++) {
            ItemStack stack = container.getItem(i);
            if (stack == null || stack.isEmpty() || !isSophisticatedBackpackItem(stack)) {
                continue;
            }
            UUID uuid = extractSbStorageUuidFromStack(stack);
            if (uuid != null) {
                backpackUuids.add(uuid);
            }
        }
    }

    private static UUID extractSbStorageUuidFromStack(ItemStack stack) {
        UUID fromComponent = extractStorageUuidFromDataComponent(stack);
        if (fromComponent != null) {
            return fromComponent;
        }
        Set<String> candidates = new HashSet<>();
        collectUuidCandidatesFromStack(stack, candidates);
        for (String candidate : candidates) {
            UUID parsed = safeParseUuid(candidate);
            if (parsed != null) {
                return parsed;
            }
        }
        return null;
    }

    private static UUID extractStorageUuidFromDataComponent(ItemStack stack) {
        DataComponentType<?> storageUuidComponent = resolveSbStorageUuidComponent();
        if (storageUuidComponent == null) {
            return null;
        }
        try {
            Object value = stack.get((DataComponentType<Object>) storageUuidComponent);
            if (value instanceof UUID uuid) {
                return uuid;
            }
        } catch (Throwable ignored) {
            // ignore component access failures
        }
        return null;
    }

    private static DataComponentType<?> resolveSbStorageUuidComponent() {
        if (SB_STORAGE_UUID_COMPONENT_RESOLVED) {
            return SB_STORAGE_UUID_COMPONENT;
        }
        synchronized (AuditSnapshot.class) {
            if (SB_STORAGE_UUID_COMPONENT_RESOLVED) {
                return SB_STORAGE_UUID_COMPONENT;
            }
            try {
                Class<?> holder = Class.forName("net.p3pp3rf1y.sophisticatedcore.init.ModCoreDataComponents");
                Field field = holder.getField("STORAGE_UUID");
                Object raw = field.get(null);
                if (raw instanceof DataComponentType<?> direct) {
                    SB_STORAGE_UUID_COMPONENT = direct;
                } else if (raw != null) {
                    Method getter = raw.getClass().getMethod("get");
                    Object component = getter.invoke(raw);
                    if (component instanceof DataComponentType<?> direct) {
                        SB_STORAGE_UUID_COMPONENT = direct;
                    }
                }
            } catch (Throwable ignored) {
                SB_STORAGE_UUID_COMPONENT = null;
            } finally {
                SB_STORAGE_UUID_COMPONENT_RESOLVED = true;
            }
            return SB_STORAGE_UUID_COMPONENT;
        }
    }

    private static boolean isSophisticatedBackpackItem(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        ResourceLocation key = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return key != null && isSophisticatedBackpackItemId(key.toString());
    }

    private static boolean isSophisticatedBackpackItemId(String itemId) {
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

    private static void collectUuidCandidatesFromStack(ItemStack stack, Set<String> uuidCandidates) {
        if (stack == null || stack.isEmpty()) {
            return;
        }
        UuidTraversalContext context = new UuidTraversalContext();

        try {
            CustomData customData = stack.get(DataComponents.CUSTOM_DATA);
            if (customData != null) {
                collectUuidCandidatesFromTag(customData.copyTag(), uuidCandidates, 0, context);
            }
        } catch (Exception ignored) {
            // ignore custom data failures
        }

        try {
            collectUuidCandidatesFromObject(stack.getComponents(), uuidCandidates, 0, context);
        } catch (Exception ignored) {
            // ignore component traversal failures
        }
    }

    private static void collectUuidCandidatesFromObject(
        Object value,
        Set<String> uuidCandidates,
        int depth,
        UuidTraversalContext context
    ) {
        if (value == null || depth > MAX_NESTED_DEPTH || !context.tryConsumeBudget()) {
            return;
        }
        if (value instanceof UUID uuid) {
            uuidCandidates.add(uuid.toString().toLowerCase());
            return;
        }
        if (value instanceof String text) {
            collectUuidCandidatesFromText(text, uuidCandidates);
            return;
        }
        if (value instanceof Tag tag) {
            collectUuidCandidatesFromTag(tag, uuidCandidates, depth + 1, context);
            return;
        }
        if (isLeafType(value.getClass())) {
            return;
        }
        if (!context.markVisited(value)) {
            return;
        }
        if (value instanceof Optional<?> optional) {
            collectUuidCandidatesFromObject(optional.orElse(null), uuidCandidates, depth + 1, context);
            return;
        }
        if (value instanceof Map<?, ?> map) {
            for (Object key : map.keySet()) {
                collectUuidCandidatesFromObject(key, uuidCandidates, depth + 1, context);
            }
            for (Object mapValue : map.values()) {
                collectUuidCandidatesFromObject(mapValue, uuidCandidates, depth + 1, context);
            }
            return;
        }
        if (value instanceof Iterable<?> iterable) {
            for (Object element : iterable) {
                collectUuidCandidatesFromObject(element, uuidCandidates, depth + 1, context);
            }
            return;
        }
        if (value.getClass().isArray()) {
            int length = Array.getLength(value);
            for (int i = 0; i < length; i++) {
                collectUuidCandidatesFromObject(Array.get(value, i), uuidCandidates, depth + 1, context);
            }
            return;
        }
        collectUuidCandidatesFromFields(value, uuidCandidates, depth, context);
    }

    private static void collectUuidCandidatesFromFields(
        Object source,
        Set<String> uuidCandidates,
        int depth,
        UuidTraversalContext context
    ) {
        Class<?> type = source.getClass();
        int inspectedLevels = 0;
        while (type != null && type != Object.class && inspectedLevels < 4) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    Object fieldValue = field.get(source);
                    if (fieldValue instanceof UUID uuid) {
                        uuidCandidates.add(uuid.toString().toLowerCase());
                        continue;
                    }
                    if (fieldValue instanceof String text) {
                        collectUuidCandidatesFromText(text, uuidCandidates);
                        continue;
                    }
                    if (!shouldInspectFieldType(field.getType())) {
                        continue;
                    }
                    collectUuidCandidatesFromObject(fieldValue, uuidCandidates, depth + 1, context);
                } catch (Exception ignored) {
                    // ignore reflective access failures
                }
            }
            type = type.getSuperclass();
            inspectedLevels++;
        }
    }

    private static void collectUuidCandidatesFromTag(
        Tag tag,
        Set<String> uuidCandidates,
        int depth,
        UuidTraversalContext context
    ) {
        if (tag == null || depth > MAX_NESTED_DEPTH || !context.tryConsumeBudget()) {
            return;
        }
        if (tag instanceof CompoundTag compound) {
            for (String key : compound.getAllKeys()) {
                collectUuidCandidatesFromText(key, uuidCandidates);
                Tag value = compound.get(key);
                if (value != null) {
                    collectUuidCandidatesFromText(value.getAsString(), uuidCandidates);
                }
                collectUuidCandidatesFromTag(value, uuidCandidates, depth + 1, context);
            }
            return;
        }
        if (tag instanceof ListTag list) {
            for (int i = 0; i < list.size(); i++) {
                collectUuidCandidatesFromTag(list.get(i), uuidCandidates, depth + 1, context);
            }
            return;
        }
        if (tag instanceof IntArrayTag intArrayTag) {
            UUID uuid = uuidFromIntArray(intArrayTag.getAsIntArray());
            if (uuid != null) {
                uuidCandidates.add(uuid.toString().toLowerCase());
            }
            return;
        }
        collectUuidCandidatesFromText(tag.getAsString(), uuidCandidates);
    }

    private static void collectUuidCandidatesFromText(String text, Set<String> uuidCandidates) {
        if (text == null || text.isBlank()) {
            return;
        }
        Matcher matcher = UUID_PATTERN.matcher(text);
        while (matcher.find()) {
            uuidCandidates.add(matcher.group().toLowerCase());
        }
    }

    private static SbStorageIndex loadSbStorageIndex(Path sbDataFile) {
        try {
            long lastModified = Files.getLastModifiedTime(sbDataFile).toMillis();
            long now = System.currentTimeMillis();
            SbStorageIndexCache cached = SB_STORAGE_INDEX_CACHE.get(sbDataFile);
            if (cached != null
                && cached.lastModifiedMs == lastModified
                && now - cached.loadedAtMs < SB_CACHE_REFRESH_MS) {
                return cached.index;
            }
            SbStorageIndex parsed = parseSbStorageIndex(sbDataFile);
            SB_STORAGE_INDEX_CACHE.put(sbDataFile, new SbStorageIndexCache(now, lastModified, parsed));
            return parsed;
        } catch (Exception e) {
            Lattice.LOGGER.debug("Load SB storage index failed: {}", sbDataFile, e);
            return SbStorageIndex.empty();
        }
    }

    private static SbStorageIndex parseSbStorageIndex(Path sbDataFile) {
        try {
            CompoundTag root = readCompressedTag(sbDataFile);
            if (root == null) {
                return SbStorageIndex.empty();
            }
            CompoundTag payload = root.contains("data", Tag.TAG_COMPOUND) ? root.getCompound("data") : root;
            ListTag backpackContents = payload.getList("backpackContents", Tag.TAG_COMPOUND);
            if (backpackContents.isEmpty()) {
                return SbStorageIndex.empty();
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
                CompoundTag contents = pair.getCompound("contents");
                contentsByUuid.put(uuid, contents);
            }
            return new SbStorageIndex(contentsByUuid);
        } catch (Exception e) {
            Lattice.LOGGER.debug("Parse SB storage file failed: {}", sbDataFile, e);
            return SbStorageIndex.empty();
        }
    }

    private static UUID readUuidFromCompound(CompoundTag compound, String key) {
        if (compound == null || key == null || key.isBlank()) {
            return null;
        }
        try {
            if (compound.hasUUID(key)) {
                return compound.getUUID(key);
            }
        } catch (Throwable ignored) {
            // ignore direct UUID read failures
        }
        return readUuidFromTag(compound.get(key), key, 0);
    }

    private static UUID readUuidFromTag(Tag tag, String keyHint, int depth) {
        if (tag == null || depth > MAX_NESTED_DEPTH) {
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
            if (keyHint != null && !keyHint.isBlank()) {
                String lowerHint = keyHint.toLowerCase();
                for (String key : compound.getAllKeys()) {
                    if (!key.toLowerCase().contains(lowerHint)) {
                        continue;
                    }
                    UUID nested = readUuidFromTag(compound.get(key), key, depth + 1);
                    if (nested != null) {
                        return nested;
                    }
                }
            }
            for (String key : compound.getAllKeys()) {
                if (!key.toLowerCase().contains("uuid")) {
                    continue;
                }
                UUID nested = readUuidFromTag(compound.get(key), key, depth + 1);
                if (nested != null) {
                    return nested;
                }
            }
            return null;
        }
        if (tag instanceof ListTag listTag && listTag.size() == 4) {
            int[] value = new int[4];
            for (int i = 0; i < 4; i++) {
                Tag element = listTag.get(i);
                if (element == null) {
                    return null;
                }
                try {
                    value[i] = Integer.parseInt(element.getAsString());
                } catch (Exception ignored) {
                    return null;
                }
            }
            return uuidFromIntArray(value);
        }
        return safeParseUuid(tag.getAsString());
    }

    private static UUID uuidFromIntArray(int[] value) {
        if (value == null || value.length != 4) {
            return null;
        }
        long most = ((long) value[0] << 32) | (value[1] & 0xFFFF_FFFFL);
        long least = ((long) value[2] << 32) | (value[3] & 0xFFFF_FFFFL);
        return new UUID(most, least);
    }

    private static UUID safeParseUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static CompoundTag readCompressedTag(Path file) throws Exception {
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

    private static Object invokeStatic(Class<?> owner, String method, Class<?>[] types, Object[] args) {
        try {
            Method m = owner.getMethod(method, types);
            return m.invoke(null, args);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void collectStackAndNested(
        ItemStack stack,
        Map<String, Integer> counts,
        Set<String> itemFilter,
        int multiplier,
        int depth
    ) {
        if (stack == null || stack.isEmpty() || multiplier <= 0 || depth > MAX_NESTED_DEPTH) {
            return;
        }

        ResourceLocation key = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (key != null) {
            String itemId = key.toString();
            if (itemFilter == null || itemFilter.isEmpty() || itemFilter.contains(itemId)) {
                mergeCount(counts, itemId, (long) stack.getCount() * multiplier);
            }
        }

        if (depth >= MAX_NESTED_DEPTH) {
            return;
        }

        int nestedMultiplier = safeMultiply(multiplier, Math.max(1, stack.getCount()));
        TraversalContext context = new TraversalContext(nestedMultiplier);
        collectNestedFromObject(stack.getComponents(), counts, itemFilter, depth + 1, context);
    }

    private static void collectNestedFromObject(
        Object value,
        Map<String, Integer> counts,
        Set<String> itemFilter,
        int depth,
        TraversalContext context
    ) {
        if (value == null || depth > MAX_NESTED_DEPTH || !context.tryConsumeBudget()) {
            return;
        }

        if (value instanceof ItemStack nestedStack) {
            collectStackAndNested(nestedStack, counts, itemFilter, context.multiplier, depth);
            return;
        }

        if (value instanceof Tag tag) {
            collectNestedFromTag(tag, counts, itemFilter, context.multiplier, depth);
            return;
        }

        if (isLeafType(value.getClass())) {
            return;
        }

        if (!context.markVisited(value)) {
            return;
        }

        if (value instanceof Optional<?> optional) {
            collectNestedFromObject(optional.orElse(null), counts, itemFilter, depth + 1, context);
            return;
        }

        if (value instanceof Map<?, ?> map) {
            for (Object entryValue : map.values()) {
                collectNestedFromObject(entryValue, counts, itemFilter, depth + 1, context);
            }
            return;
        }

        if (value instanceof Iterable<?> iterable) {
            for (Object element : iterable) {
                collectNestedFromObject(element, counts, itemFilter, depth + 1, context);
            }
            return;
        }

        if (value.getClass().isArray()) {
            int length = Array.getLength(value);
            for (int i = 0; i < length; i++) {
                collectNestedFromObject(Array.get(value, i), counts, itemFilter, depth + 1, context);
            }
            return;
        }

        collectNestedFromFields(value, counts, itemFilter, depth, context);
    }

    private static void collectNestedFromFields(
        Object source,
        Map<String, Integer> counts,
        Set<String> itemFilter,
        int depth,
        TraversalContext context
    ) {
        Class<?> type = source.getClass();
        int inspectedLevels = 0;
        while (type != null && type != Object.class && inspectedLevels < 3) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
                    continue;
                }
                Class<?> fieldType = field.getType();
                if (!shouldInspectFieldType(fieldType)) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    Object child = field.get(source);
                    collectNestedFromObject(child, counts, itemFilter, depth + 1, context);
                } catch (Exception ignored) {
                    // ignore reflective access failures
                }
            }
            type = type.getSuperclass();
            inspectedLevels++;
        }
    }

    private static void collectNestedFromTag(
        Tag tag,
        Map<String, Integer> counts,
        Set<String> itemFilter,
        int multiplier,
        int depth
    ) {
        if (tag == null || depth > MAX_NESTED_DEPTH || multiplier <= 0) {
            return;
        }

        if (tag instanceof CompoundTag compound) {
            collectItemIfStackLike(compound, counts, itemFilter, multiplier);
            for (String key : compound.getAllKeys()) {
                collectNestedFromTag(compound.get(key), counts, itemFilter, multiplier, depth + 1);
            }
            return;
        }

        if (tag instanceof ListTag list) {
            for (int i = 0; i < list.size(); i++) {
                collectNestedFromTag(list.get(i), counts, itemFilter, multiplier, depth + 1);
            }
        }
    }

    private static void collectItemIfStackLike(
        CompoundTag compound,
        Map<String, Integer> counts,
        Set<String> itemFilter,
        int multiplier
    ) {
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
        mergeCount(counts, normalizedId, (long) count * multiplier);
    }

    private static boolean shouldInspectFieldType(Class<?> fieldType) {
        if (fieldType.isPrimitive()) {
            return false;
        }
        return ItemStack.class.isAssignableFrom(fieldType)
            || Tag.class.isAssignableFrom(fieldType)
            || Iterable.class.isAssignableFrom(fieldType)
            || Map.class.isAssignableFrom(fieldType)
            || Optional.class.isAssignableFrom(fieldType)
            || fieldType.isArray();
    }

    private static boolean isLeafType(Class<?> type) {
        return type.isPrimitive()
            || type.isEnum()
            || Number.class.isAssignableFrom(type)
            || CharSequence.class.isAssignableFrom(type)
            || Boolean.class == type
            || Class.class == type;
    }

    private static void mergeCount(Map<String, Integer> counts, String itemId, long delta) {
        if (delta <= 0) {
            return;
        }
        int safeDelta = delta > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) delta;
        counts.merge(itemId, safeDelta, (a, b) -> {
            long sum = (long) a + b;
            return sum > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) sum;
        });
    }

    private static int safeMultiply(int a, int b) {
        long value = (long) a * b;
        if (value <= 0) {
            return 1;
        }
        if (value > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return (int) value;
    }

    private static final class TraversalContext {
        private final int multiplier;
        private int remainingBudget;
        private final IdentityHashMap<Object, Boolean> visited = new IdentityHashMap<>();

        private TraversalContext(int multiplier) {
            this.multiplier = multiplier;
            this.remainingBudget = MAX_OBJECT_TRAVERSAL_PER_STACK;
        }

        private boolean tryConsumeBudget() {
            if (remainingBudget <= 0) {
                return false;
            }
            remainingBudget--;
            return true;
        }

        private boolean markVisited(Object value) {
            return visited.put(value, Boolean.TRUE) == null;
        }
    }

    private static final class UuidTraversalContext {
        private int remainingBudget = MAX_OBJECT_TRAVERSAL_PER_STACK;
        private final IdentityHashMap<Object, Boolean> visited = new IdentityHashMap<>();

        private boolean tryConsumeBudget() {
            if (remainingBudget <= 0) {
                return false;
            }
            remainingBudget--;
            return true;
        }

        private boolean markVisited(Object value) {
            return visited.put(value, Boolean.TRUE) == null;
        }
    }

    private static final class UuidDepth {
        private final UUID uuid;
        private final int depth;

        private UuidDepth(UUID uuid, int depth) {
            this.uuid = uuid;
            this.depth = depth;
        }
    }

    private static final class SbStorageIndex {
        private static final SbStorageIndex EMPTY = new SbStorageIndex(Collections.emptyMap());

        private final Map<UUID, CompoundTag> contentsByUuid;

        private SbStorageIndex(Map<UUID, CompoundTag> contentsByUuid) {
            this.contentsByUuid = contentsByUuid;
        }

        private static SbStorageIndex empty() {
            return EMPTY;
        }
    }

    private static final class SbStorageIndexCache {
        private final long loadedAtMs;
        private final long lastModifiedMs;
        private final SbStorageIndex index;

        private SbStorageIndexCache(long loadedAtMs, long lastModifiedMs, SbStorageIndex index) {
            this.loadedAtMs = loadedAtMs;
            this.lastModifiedMs = lastModifiedMs;
            this.index = index;
        }
    }
}
