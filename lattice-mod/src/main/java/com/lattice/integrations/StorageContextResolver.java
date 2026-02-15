package com.lattice.integrations;

import com.lattice.track.StorageContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.UUID;

public final class StorageContextResolver {
    private StorageContextResolver() {
    }

    public static StorageContext resolve(AbstractContainerMenu menu, ServerPlayer player) {
        String className = menu.getClass().getName().toLowerCase();
        String traceId = UUID.randomUUID().toString();

        if (className.contains("sophisticatedstorage")) {
            String storageId = resolveSophisticatedStorageId(menu, player);
            return new StorageContext("sophisticatedstorage", storageId, "player", traceId);
        }
        if (className.contains("sophisticatedbackpacks")) {
            String storageId = resolveSophisticatedBackpackId(menu, player);
            return new StorageContext("sophisticatedbackpacks", storageId, "player", traceId);
        }
        return new StorageContext("vanilla", menu.getClass().getSimpleName() + "-" + System.identityHashCode(menu), "player", traceId);
    }

    private static String resolveSophisticatedStorageId(AbstractContainerMenu menu, ServerPlayer player) {
        try {
            Method method = menu.getClass().getMethod("getStorageBlockEntity");
            Object blockEntity = method.invoke(menu);
            if (blockEntity != null) {
                Method wrapperMethod = blockEntity.getClass().getMethod("getStorageWrapper");
                Object wrapper = wrapperMethod.invoke(blockEntity);
                if (wrapper != null) {
                    try {
                        Field field = wrapper.getClass().getDeclaredField("contentsUuid");
                        field.setAccessible(true);
                        Object uuid = field.get(wrapper);
                        if (uuid != null) {
                            return "ss:" + uuid.toString();
                        }
                    } catch (Exception ignored) {
                        // fallback
                    }
                }
                Method posMethod = blockEntity.getClass().getMethod("getBlockPos");
                Object pos = posMethod.invoke(blockEntity);
                if (pos instanceof BlockPos blockPos) {
                    return "ss:" + player.level().dimension().location() + ":" + blockPos.getX() + "," + blockPos.getY() + "," + blockPos.getZ();
                }
            }
        } catch (Exception ignored) {
            // fallback
        }
        return "ss:" + player.getUUID() + ":" + System.identityHashCode(menu);
    }

    private static String resolveSophisticatedBackpackId(AbstractContainerMenu menu, ServerPlayer player) {
        return "sb:" + player.getUUID() + ":" + System.identityHashCode(menu);
    }
}
