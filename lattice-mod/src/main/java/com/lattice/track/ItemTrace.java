package com.lattice.track;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

public final class ItemTrace {
    private ItemTrace() {
    }

    public static String hashComponents(ItemStack stack) {
        return Integer.toHexString(stack.getComponents().hashCode());
    }

    public static String fingerprint(ItemStack stack) {
        ResourceLocation key = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return fingerprint(key.toString(), hashComponents(stack), ItemOrigin.getOriginId(stack));
    }

    public static String fingerprint(String itemId, String nbtHash, String originId) {
        String origin = originId != null ? originId : "";
        return itemId + ":" + nbtHash + ":" + origin;
    }
}
