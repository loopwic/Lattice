package com.lattice.audit;

import com.lattice.Lattice;
import com.lattice.events.EventPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class AuditSnapshot {
    private static final int MAX_NESTED_DEPTH = 8;
    private static final int MAX_OBJECT_TRAVERSAL_PER_STACK = 4096;

    private AuditSnapshot() {
    }

    public static int enqueuePlayerSnapshot(ServerPlayer player, Set<String> itemFilter) {
        String snapshotId = UUID.randomUUID().toString();
        Map<String, Integer> counts = new HashMap<>();
        collectCounts(player.getInventory(), counts, itemFilter);
        collectCounts(player.getEnderChestInventory(), counts, itemFilter);
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
}
