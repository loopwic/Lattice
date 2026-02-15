package com.lattice.mixin;

import com.lattice.events.EventFactory;
import com.lattice.track.ContextHolder;
import com.lattice.track.ItemOrigin;
import com.lattice.track.StorageContext;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Player.class)
public class PlayerMixin {
    @Inject(method = "addItem", at = @At("RETURN"))
    private void lattice$afterAddItem(ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValue()) {
            return;
        }
        if (!((Object) this instanceof ServerPlayer player)) {
            return;
        }
        if (ContextHolder.shouldSkipAddItem(player, stack)) {
            return;
        }
        StorageContext context = ContextHolder.peekContext(player);
        String originType;
        if (context != null && "world".equals(context.storageId)) {
            originType = "world_pickup";
        } else if (context != null) {
            originType = resolveOriginType(context);
        } else {
            originType = "world_pickup";
        }
        ItemOrigin.ensureOrigin(stack, originType, originType);
        EventFactory.recordAcquire(player, stack, originType, originType, "player_add", "", context);
    }

    private String resolveOriginType(StorageContext context) {
        if (!"vanilla".equals(context.storageMod)) {
            return "container_click";
        }
        String id = context.storageId == null ? "" : context.storageId.toLowerCase();
        if (id.contains("smithing")) {
            return "smithing";
        }
        if (id.contains("stonecutter")) {
            return "stonecutting";
        }
        if (id.contains("grindstone")) {
            return "grindstone";
        }
        if (id.contains("anvil")) {
            return "anvil";
        }
        if (id.contains("brewing")) {
            return "brewing";
        }
        if (id.contains("loom")) {
            return "loom";
        }
        if (id.contains("cartography")) {
            return "cartography";
        }
        if (id.contains("enchant")) {
            return "enchant";
        }
        if (id.contains("merchant") || id.contains("trade")) {
            return "trade";
        }
        if (id.contains("smoker") || id.contains("blast") || id.contains("furnace")) {
            return "smelt";
        }
        if (id.contains("craft")) {
            return "craft";
        }
        return "container_click";
    }
}
