package com.lattice.integrations;

import com.lattice.Lattice;
import com.lattice.events.EventPayload;
import com.lattice.events.EventQueue;
import com.lattice.track.ItemOrigin;
import com.lattice.track.ItemTrace;
import com.lattice.track.StorageContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.UUID;

public final class Rs2Integration {
    private static EventQueue queue;

    private Rs2Integration() {
    }

    public static void initialize(EventQueue eventQueue) {
        queue = eventQueue;
        Lattice.LOGGER.info("RS2 integration enabled");
    }

    public static void onInsert(Object rootStorage, Object resource, long amount, Object action, Object actor) {
        if (!isExecute(action) || amount <= 0) {
            return;
        }
        ItemStack stack = toItemStack(resource, amount);
        if (stack == null || stack.isEmpty()) {
            return;
        }
        StorageContext context = new StorageContext("rs2", storageId(rootStorage), actorType(actor), UUID.randomUUID().toString());
        recordTransfer(stack, amount, context, "rs2_insert", actor);
    }

    public static void onExtract(Object rootStorage, Object resource, long amount, Object action, Object actor) {
        if (!isExecute(action) || amount <= 0) {
            return;
        }
        ItemStack stack = toItemStack(resource, amount);
        if (stack == null || stack.isEmpty()) {
            return;
        }
        StorageContext context = new StorageContext("rs2", storageId(rootStorage), actorType(actor), UUID.randomUUID().toString());
        recordTransfer(stack, amount, context, "rs2_extract", actor);
    }

    private static void recordTransfer(ItemStack stack, long amount, StorageContext context, String sourceType, Object actor) {
        EventPayload payload = new EventPayload();
        payload.event_id = UUID.randomUUID().toString();
        payload.event_time = System.currentTimeMillis();
        payload.server_id = Lattice.getConfig().serverId;
        payload.event_type = "TRANSFER";
        String actorName = actorName(actor);
        Optional<ServerPlayer> player = resolvePlayer(actorName);
        payload.player_uuid = player.map(p -> p.getGameProfile().getId().toString()).orElse("");
        payload.player_name = player.map(p -> p.getGameProfile().getName()).orElse(actorName != null ? actorName : "");
        ResourceLocation key = BuiltInRegistries.ITEM.getKey(stack.getItem());
        payload.item_id = key.toString();
        payload.count = (int) amount;
        payload.nbt_hash = ItemTrace.hashComponents(stack);
        payload.origin_id = ItemOrigin.getOriginId(stack);
        payload.origin_type = ItemOrigin.getOriginType(stack);
        payload.origin_ref = ItemOrigin.getOriginRef(stack);
        payload.source_type = sourceType;
        payload.source_ref = "";
        payload.storage_mod = context.storageMod;
        payload.storage_id = context.storageId;
        payload.actor_type = context.actorType;
        payload.trace_id = context.traceId;
        payload.item_fingerprint = ItemTrace.fingerprint(payload.item_id, payload.nbt_hash, payload.origin_id);
        if (player.isPresent()) {
            BlockPos pos = player.get().blockPosition();
            payload.dim = player.get().level().dimension().location().toString();
            payload.x = pos.getX();
            payload.y = pos.getY();
            payload.z = pos.getZ();
        }
        queue.enqueue(payload);
    }

    private static Optional<ServerPlayer> resolvePlayer(String name) {
        MinecraftServer server = queue != null ? queue.getServer() : null;
        if (server == null || name == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(server.getPlayerList().getPlayerByName(name));
    }

    private static String storageId(Object rootStorage) {
        return "rs2:" + System.identityHashCode(rootStorage);
    }

    private static String actorType(Object actor) {
        if (actor == null) {
            return "unknown";
        }
        String className = actor.getClass().getName().toLowerCase();
        if (className.contains("playeractor")) {
            return "player";
        }
        if (className.contains("networknodeactor")) {
            return "automation";
        }
        return "unknown";
    }

    private static String actorName(Object actor) {
        if (actor == null) {
            return null;
        }
        try {
            Method method = actor.getClass().getMethod("getName");
            Object result = method.invoke(actor);
            return result != null ? result.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isExecute(Object action) {
        if (action == null) {
            return true;
        }
        return "EXECUTE".equals(action.toString());
    }

    private static ItemStack toItemStack(Object resource, long amount) {
        if (resource == null) {
            return ItemStack.EMPTY;
        }
        try {
            Method method = resource.getClass().getMethod("toItemStack", long.class);
            Object stack = method.invoke(resource, amount);
            if (stack instanceof ItemStack itemStack) {
                return itemStack;
            }
        } catch (NoSuchMethodException ignored) {
            try {
                Method method = resource.getClass().getMethod("toItemStack");
                Object stack = method.invoke(resource);
                if (stack instanceof ItemStack itemStack) {
                    if (itemStack.getCount() != amount) {
                        return itemStack.copyWithCount((int) amount);
                    }
                    return itemStack;
                }
            } catch (Exception ignored2) {
                return ItemStack.EMPTY;
            }
        } catch (Exception e) {
            return ItemStack.EMPTY;
        }
        return ItemStack.EMPTY;
    }
}
