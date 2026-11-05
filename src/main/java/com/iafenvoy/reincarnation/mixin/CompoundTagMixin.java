package com.iafenvoy.reincarnation.mixin;

import com.iafenvoy.reincarnation.reference.ReferenceCarrier;
import com.iafenvoy.reincarnation.reference.ReferencePersistence;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(CompoundTag.class)
public abstract class CompoundTagMixin implements ReferenceCarrier {
    @Unique
    private boolean reincarnation$carrier;

    @Override
    public boolean reincarnation$isCarrier() {
        return this.reincarnation$carrier;
    }

    @Override
    public void reincarnation$markCarrier() {
        this.reincarnation$carrier = true;
    }

    @Inject(method = "copy()Lnet/minecraft/nbt/CompoundTag;", at = @At("RETURN"))
    private void reincarnation$copy(CallbackInfoReturnable<CompoundTag> cir) {
        ReferencePersistence.copyTransient(this, cir.getReturnValue());
    }
}
