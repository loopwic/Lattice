package com.lattice.track;

import com.lattice.Lattice;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class ContextHolder {
    private static final Map<UUID, ContextRecord> CONTEXTS = new HashMap<>();
    private static final Map<UUID, SkipRecord> SKIP_ADD_ITEM = new HashMap<>();

    private ContextHolder() {
    }

    public static void setContext(ServerPlayer player, StorageContext context) {
        CONTEXTS.put(player.getUUID(), new ContextRecord(context, System.currentTimeMillis()));
    }

    public static StorageContext peekContext(ServerPlayer player) {
        ContextRecord record = CONTEXTS.get(player.getUUID());
        if (record == null) {
            return null;
        }
        long now = System.currentTimeMillis();
        if (now - record.timestamp > Lattice.getConfig().contextWindowMs) {
            CONTEXTS.remove(player.getUUID());
            return null;
        }
        return record.context;
    }

    public static void markPickup(ServerPlayer player) {
        StorageContext context = new StorageContext("vanilla", "world", "player", newTraceId());
        setContext(player, context);
    }

    public static String newTraceId() {
        return UUID.randomUUID().toString();
    }

    public static void skipNextAddItem(ServerPlayer player, ItemStack stack) {
        SKIP_ADD_ITEM.put(player.getUUID(), new SkipRecord(ItemTrace.fingerprint(stack), System.currentTimeMillis()));
    }

    public static boolean shouldSkipAddItem(ServerPlayer player, ItemStack stack) {
        SkipRecord record = SKIP_ADD_ITEM.get(player.getUUID());
        if (record == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (now - record.timestamp > 1000) {
            SKIP_ADD_ITEM.remove(player.getUUID());
            return false;
        }
        String fingerprint = ItemTrace.fingerprint(stack);
        if (record.fingerprint.equals(fingerprint)) {
            SKIP_ADD_ITEM.remove(player.getUUID());
            return true;
        }
        return false;
    }

    private record ContextRecord(StorageContext context, long timestamp) {
    }

    private record SkipRecord(String fingerprint, long timestamp) {
    }
}
