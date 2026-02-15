package com.lattice.mixin;

import com.lattice.events.EventFactory;
import com.lattice.track.ContextHolder;
import com.lattice.track.ItemTrace;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Inventory.class)
public abstract class InventoryMixin {
    @Shadow @Final public Player player;
    @Shadow public abstract ItemStack getItem(int slot);

    @Unique private ItemStack lattice$beforeStack = ItemStack.EMPTY;

    @Inject(method = "setItem", at = @At("HEAD"), require = 0)
    private void lattice$beforeSetItem(int slot, ItemStack stack, CallbackInfo ci) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            lattice$beforeStack = ItemStack.EMPTY;
            return;
        }
        if (serverPlayer.tickCount < 40) {
            lattice$beforeStack = ItemStack.EMPTY;
            return;
        }
        lattice$beforeStack = getItem(slot).copy();
    }

    @Inject(method = "setItem", at = @At("TAIL"), require = 0)
    private void lattice$afterSetItem(int slot, ItemStack stack, CallbackInfo ci) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return;
        }
        if (serverPlayer.tickCount < 40) {
            return;
        }
        if (ContextHolder.peekContext(serverPlayer) != null) {
            return;
        }
        if (stack == null || stack.isEmpty()) {
            return;
        }
        int beforeCount = 0;
        if (!lattice$beforeStack.isEmpty() && isSameAuditKey(lattice$beforeStack, stack)) {
            beforeCount = lattice$beforeStack.getCount();
        }
        int delta = stack.getCount() - beforeCount;
        if (delta <= 0) {
            return;
        }
        EventFactory.recordAuditAcquire(serverPlayer, stack, delta);
    }

    @Unique
    private boolean isSameAuditKey(ItemStack left, ItemStack right) {
        ResourceLocation leftKey = BuiltInRegistries.ITEM.getKey(left.getItem());
        ResourceLocation rightKey = BuiltInRegistries.ITEM.getKey(right.getItem());
        if (!leftKey.equals(rightKey)) {
            return false;
        }
        String leftHash = ItemTrace.hashComponents(left);
        String rightHash = ItemTrace.hashComponents(right);
        return leftHash.equals(rightHash);
    }
}
