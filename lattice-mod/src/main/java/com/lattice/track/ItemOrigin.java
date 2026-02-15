package com.lattice.track;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import java.util.UUID;

public final class ItemOrigin {
    private static final String ROOT_KEY = "lattice_origin";
    private static final String ORIGIN_ID = "origin_id";
    private static final String ORIGIN_TYPE = "origin_type";
    private static final String ORIGIN_REF = "origin_ref";
    private static final String ORIGIN_TIME = "origin_time";

    private ItemOrigin() {
    }

    public static void ensureOrigin(ItemStack stack, String originType, String originRef) {
        if (stack == null || stack.isEmpty()) {
            return;
        }
        CompoundTag root = getRootTag(stack);
        CompoundTag origin = root.getCompound(ROOT_KEY);
        if (!origin.contains(ORIGIN_ID)) {
            origin.putString(ORIGIN_ID, UUID.randomUUID().toString());
            origin.putString(ORIGIN_TYPE, originType != null ? originType : "unknown");
            origin.putString(ORIGIN_REF, originRef != null ? originRef : "");
            origin.putLong(ORIGIN_TIME, System.currentTimeMillis());
            root.put(ROOT_KEY, origin);
            setRootTag(stack, root);
        }
    }

    public static String getOriginId(ItemStack stack) {
        CompoundTag origin = getOriginTag(stack);
        return origin != null && origin.contains(ORIGIN_ID) ? origin.getString(ORIGIN_ID) : null;
    }

    public static String getOriginType(ItemStack stack) {
        CompoundTag origin = getOriginTag(stack);
        return origin != null && origin.contains(ORIGIN_TYPE) ? origin.getString(ORIGIN_TYPE) : null;
    }

    public static String getOriginRef(ItemStack stack) {
        CompoundTag origin = getOriginTag(stack);
        return origin != null && origin.contains(ORIGIN_REF) ? origin.getString(ORIGIN_REF) : null;
    }

    private static CompoundTag getOriginTag(ItemStack stack) {
        CompoundTag root = getRootTag(stack);
        if (root == null || !root.contains(ROOT_KEY)) {
            return null;
        }
        return root.getCompound(ROOT_KEY);
    }

    private static CompoundTag getRootTag(ItemStack stack) {
        CustomData customData = stack.get(DataComponents.CUSTOM_DATA);
        if (customData == null) {
            return new CompoundTag();
        }
        return customData.copyTag();
    }

    private static void setRootTag(ItemStack stack, CompoundTag tag) {
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
    }
}
