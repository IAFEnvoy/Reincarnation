package com.iafenvoy.reincarnation.mixin;

import com.iafenvoy.reincarnation.api.LifeReferences;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.SynchedEntityData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(SynchedEntityData.class)
public abstract class SynchedEntityDataMixin {
    @Shadow
    public abstract <T> T get(EntityDataAccessor<T> accessor);

    @ModifyVariable(method = "set(Lnet/minecraft/network/syncher/EntityDataAccessor;Ljava/lang/Object;Z)V", at = @At("HEAD"), argsOnly = true, name = "forceDirty")
    private <T> boolean reincarnation$forceReferenceChange(boolean forceDirty, EntityDataAccessor<T> accessor, T value, boolean original) {
        return forceDirty || LifeReferences.metadataChanged(this.get(accessor), value);
    }
}
