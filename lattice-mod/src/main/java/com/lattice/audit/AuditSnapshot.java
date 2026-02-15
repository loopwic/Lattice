package com.lattice.audit;

import com.lattice.Lattice;
import com.lattice.events.EventPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class AuditSnapshot {
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
            ResourceLocation key = BuiltInRegistries.ITEM.getKey(stack.getItem());
            if (key == null) {
                continue;
            }
            String itemId = key.toString();
            if (itemFilter != null && !itemFilter.isEmpty() && !itemFilter.contains(itemId)) {
                continue;
            }
            int count = stack.getCount();
            counts.merge(itemId, count, Integer::sum);
        }
    }
}
