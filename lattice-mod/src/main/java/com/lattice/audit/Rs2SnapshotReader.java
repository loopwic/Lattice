package com.lattice.audit;

import com.lattice.Lattice;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class Rs2SnapshotReader {
    private static final String STORAGE_COMPONENT_CLASS =
        "com.refinedmods.refinedstorage.api.network.storage.StorageNetworkComponent";
    private static final String ACTOR_CLASS =
        "com.refinedmods.refinedstorage.api.storage.Actor";

    private Rs2SnapshotReader() {
    }

    static Map<String, Integer> readNetworkItems(Object network, Set<String> itemFilter) {
        Map<String, Integer> counts = new HashMap<>();
        try {
            Class<?> storageComponentClass = Class.forName(STORAGE_COMPONENT_CLASS);
            Class<?> actorClass = Class.forName(ACTOR_CLASS);

            Method getComponent = network.getClass().getMethod("getComponent", Class.class);
            Object storageComponent = getComponent.invoke(network, storageComponentClass);
            if (storageComponent == null) {
                return counts;
            }

            Method getResources = storageComponent.getClass().getMethod("getResources", Class.class);
            Object listObj = getResources.invoke(storageComponent, actorClass);
            if (!(listObj instanceof List<?> list)) {
                return counts;
            }

            for (Object tracked : list) {
                if (tracked == null) {
                    continue;
                }
                Object resourceAmount = invokeNoArg(tracked, "resourceAmount");
                if (resourceAmount == null) {
                    continue;
                }
                Object resource = invokeNoArg(resourceAmount, "resource");
                Object amountObj = invokeNoArg(resourceAmount, "amount");
                if (resource == null || !(amountObj instanceof Long amountLong)) {
                    continue;
                }
                long amount = amountLong;
                if (amount <= 0) {
                    continue;
                }
                String itemId = resolveItemId(resource, amount);
                if (itemId == null || itemId.isEmpty()) {
                    continue;
                }
                if (itemFilter != null && !itemFilter.isEmpty() && !itemFilter.contains(itemId)) {
                    continue;
                }
                int count = amount > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) amount;
                if (amount > Integer.MAX_VALUE) {
                    Lattice.LOGGER.warn("RS2 snapshot amount truncated for {}: {}", itemId, amount);
                }
                counts.merge(itemId, count, Integer::sum);
            }
        } catch (ClassNotFoundException ignored) {
            // RS2 not present
        } catch (Exception e) {
            Lattice.LOGGER.warn("RS2 snapshot failed", e);
        }
        return counts;
    }

    private static String resolveItemId(Object resource, long amount) {
        try {
            Object stackObj = invoke(resource, "toItemStack", new Class<?>[]{long.class}, new Object[]{amount});
            if (stackObj instanceof ItemStack stack) {
                ResourceLocation key = BuiltInRegistries.ITEM.getKey(stack.getItem());
                return key != null ? key.toString() : null;
            }
        } catch (Exception ignored) {
            // fallback below
        }
        try {
            Object itemObj = invokeNoArg(resource, "item");
            if (itemObj instanceof Item item) {
                ResourceLocation key = BuiltInRegistries.ITEM.getKey(item);
                return key != null ? key.toString() : null;
            }
        } catch (Exception ignored) {
            // ignore
        }
        return null;
    }

    private static Object invokeNoArg(Object target, String method) throws Exception {
        Method m = target.getClass().getMethod(method);
        return m.invoke(target);
    }

    private static Object invoke(Object target, String method, Class<?>[] types, Object[] args) throws Exception {
        Method m = target.getClass().getMethod(method, types);
        return m.invoke(target, args);
    }
}
