package com.lattice.mixin;

import com.lattice.events.EventFactory;
import com.lattice.track.ContextHolder;
import com.lattice.track.StorageContext;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.FurnaceResultSlot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FurnaceResultSlot.class)
public class FurnaceResultSlotMixin {
    @Inject(method = "onTake", at = @At("HEAD"))
    private void lattice$onTake(Player player, ItemStack stack, CallbackInfo ci) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return;
        }
        StorageContext context = ContextHolder.peekContext(serverPlayer);
        EventFactory.recordAcquire(serverPlayer, stack, "smelt", "smelt", "smelt", "", context);
        ContextHolder.skipNextAddItem(serverPlayer, stack);
    }
}
