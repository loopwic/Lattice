package com.lattice.mixin.compat;

import com.lattice.integrations.IntegrationManager;
import com.lattice.integrations.Rs2Integration;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.Coerce;

@Pseudo
@Mixin(targets = "com.refinedmods.refinedstorage.api.storage.root.RootStorageImpl")
public class Rs2RootStorageMixin {
    @Inject(method = "insert", at = @At("TAIL"), require = 0)
    private void lattice$afterInsert(@Coerce Object resource,
                                         long amount,
                                         @Coerce Object action,
                                         @Coerce Object actor,
                                         CallbackInfoReturnable<Long> cir) {
        if (!IntegrationManager.isRs2Enabled()) {
            return;
        }
        long inserted = cir.getReturnValue() != null ? cir.getReturnValue() : 0L;
        if (inserted > 0) {
            Rs2Integration.onInsert(this, resource, inserted, action, actor);
        }
    }

    @Inject(method = "extract", at = @At("TAIL"), require = 0)
    private void lattice$afterExtract(@Coerce Object resource,
                                          long amount,
                                          @Coerce Object action,
                                          @Coerce Object actor,
                                          CallbackInfoReturnable<Long> cir) {
        if (!IntegrationManager.isRs2Enabled()) {
            return;
        }
        long extracted = cir.getReturnValue() != null ? cir.getReturnValue() : 0L;
        if (extracted > 0) {
            Rs2Integration.onExtract(this, resource, extracted, action, actor);
        }
    }
}
