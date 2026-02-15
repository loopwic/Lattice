package com.lattice.events;

import com.lattice.Lattice;
import com.lattice.track.ContextHolder;
import com.lattice.track.ItemOrigin;
import com.lattice.track.ItemTrace;
import com.lattice.track.StorageContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.UUID;

public final class EventFactory {
    private EventFactory() {
    }

    public static void recordAcquire(ServerPlayer player,
                                     ItemStack stack,
                                     String originType,
                                     String originRef,
                                     String sourceType,
                                     String sourceRef,
                                     StorageContext context) {
        ItemOrigin.ensureOrigin(stack, originType, originRef);
        EventPayload payload = buildBase(player, stack, context);
        payload.event_type = "ACQUIRE";
        payload.origin_id = ItemOrigin.getOriginId(stack);
        payload.origin_type = ItemOrigin.getOriginType(stack);
        payload.origin_ref = ItemOrigin.getOriginRef(stack);
        payload.source_type = sourceType;
        payload.source_ref = sourceRef;
        Lattice.getEventQueue().enqueue(payload);
    }

    public static void recordTransfer(ServerPlayer player,
                                      ItemStack stack,
                                      int count,
                                      String sourceType,
                                      String sourceRef,
                                      StorageContext context) {
        EventPayload payload = buildBase(player, stack, context);
        payload.event_type = "TRANSFER";
        payload.count = count;
        payload.source_type = sourceType;
        payload.source_ref = sourceRef;
        Lattice.getEventQueue().enqueue(payload);
    }

    public static void recordAuditAcquire(ServerPlayer player, ItemStack stack, int count) {
        EventPayload payload = buildBase(player, stack, null);
        payload.event_type = "ACQUIRE";
        payload.count = count;
        payload.origin_id = ItemOrigin.getOriginId(stack);
        payload.origin_type = "inventory_audit";
        payload.origin_ref = "inventory_audit";
        payload.source_type = "inventory_audit";
        payload.source_ref = "";
        payload.trace_id = ContextHolder.newTraceId();
        payload.item_fingerprint = ItemTrace.fingerprint(payload.item_id, payload.nbt_hash, payload.origin_id);
        Lattice.getEventQueue().enqueue(payload);
    }

    public static EventPayload buildBase(ServerPlayer player, ItemStack stack, StorageContext context) {
        EventPayload payload = new EventPayload();
        payload.event_id = UUID.randomUUID().toString();
        payload.event_time = System.currentTimeMillis();
        payload.server_id = Lattice.getConfig().serverId;
        payload.player_uuid = player.getGameProfile().getId().toString();
        payload.player_name = player.getGameProfile().getName();
        ResourceLocation key = BuiltInRegistries.ITEM.getKey(stack.getItem());
        payload.item_id = key.toString();
        payload.count = stack.getCount();
        payload.nbt_hash = ItemTrace.hashComponents(stack);
        payload.origin_id = ItemOrigin.getOriginId(stack);
        payload.origin_type = ItemOrigin.getOriginType(stack);
        payload.origin_ref = ItemOrigin.getOriginRef(stack);
        payload.storage_mod = context != null ? context.storageMod : null;
        payload.storage_id = context != null ? context.storageId : null;
        payload.actor_type = context != null ? context.actorType : null;
        payload.trace_id = context != null ? context.traceId : ContextHolder.newTraceId();
        payload.item_fingerprint = ItemTrace.fingerprint(payload.item_id, payload.nbt_hash, payload.origin_id);
        payload.dim = player.level().dimension().location().toString();
        BlockPos pos = player.blockPosition();
        payload.x = pos.getX();
        payload.y = pos.getY();
        payload.z = pos.getZ();
        return payload;
    }
}
