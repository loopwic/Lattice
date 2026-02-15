package com.lattice.mixin;

import com.lattice.track.ContextHolder;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemEntity.class)
public class ItemEntityMixin {
    @Inject(method = "playerTouch", at = @At("HEAD"))
    private void lattice$beforePickup(Player player, CallbackInfo ci) {
        if (player instanceof ServerPlayer serverPlayer) {
            ContextHolder.markPickup(serverPlayer);
        }
    }
}
