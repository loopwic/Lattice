package com.lattice.mixin;

import com.lattice.events.EventFactory;
import com.lattice.track.ContextHolder;
import com.lattice.track.StorageContext;
import com.lattice.integrations.StorageContextResolver;
import net.minecraft.core.NonNullList;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;
import java.util.List;

@Mixin(AbstractContainerMenu.class)
public abstract class AbstractContainerMenuMixin {
    @Shadow public abstract Slot getSlot(int index);
    @Shadow public abstract ItemStack getCarried();
    @Shadow @Final public NonNullList<Slot> slots;

    @Unique private ItemStack lattice$beforeSlot = ItemStack.EMPTY;
    @Unique private int lattice$slotId = -1;
    @Unique private StorageContext lattice$context;

    @Inject(method = "clicked", at = @At("HEAD"))
    private void lattice$beforeClick(int slotId, int button, ClickType clickType, Player player, CallbackInfo ci) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return;
        }
        lattice$slotId = slotId;
        lattice$beforeSlot = ItemStack.EMPTY;
        if (slotId >= 0 && slotId < slots.size()) {
            lattice$beforeSlot = getSlot(slotId).getItem().copy();
        }
        lattice$context = StorageContextResolver.resolve((AbstractContainerMenu) (Object) this, serverPlayer);
        ContextHolder.setContext(serverPlayer, lattice$context);
    }

    @Inject(method = "clicked", at = @At("TAIL"))
    private void lattice$afterClick(int slotId, int button, ClickType clickType, Player player, CallbackInfo ci) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return;
        }
        if (slotId < 0 || slotId >= slots.size()) {
            return;
        }
        Slot slot = getSlot(slotId);
        if (isPlayerSlot(slot, serverPlayer)) {
            return;
        }
        ItemStack after = slot.getItem();
        int beforeCount = lattice$beforeSlot.isEmpty() ? 0 : lattice$beforeSlot.getCount();
        int afterCount = after.isEmpty() ? 0 : after.getCount();
        int delta = afterCount - beforeCount;
        if (delta == 0) {
            return;
        }
        ItemStack reference = delta > 0 ? after : lattice$beforeSlot;
        StorageContext context = lattice$context;
        String sourceType = delta > 0 ? "container_put" : "container_take";
        EventFactory.recordTransfer(serverPlayer, reference, Math.abs(delta), sourceType, "", context);
    }

    @Unique
    private boolean isPlayerSlot(Slot slot, ServerPlayer player) {
        try {
            Field field = Slot.class.getDeclaredField("container");
            field.setAccessible(true);
            Object container = field.get(slot);
            return container == player.getInventory();
        } catch (Exception e) {
            return false;
        }
    }
}
