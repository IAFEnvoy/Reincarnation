package com.iafenvoy.reincarnation.mixin;

import com.iafenvoy.reincarnation.reference.ReferencePersistence;
import com.iafenvoy.reincarnation.reference.ReferenceStamp;
import com.iafenvoy.reincarnation.reference.StampedTag;
import net.minecraft.nbt.IntArrayTag;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(IntArrayTag.class)
public abstract class IntArrayTagMixin implements StampedTag {
    @Unique
    private @Nullable ReferenceStamp reincarnation$stamp;

    @Override
    public @Nullable ReferenceStamp reincarnation$stamp() {
        return this.reincarnation$stamp;
    }

    @Override
    public void reincarnation$stamp(@Nullable ReferenceStamp stamp) {
        this.reincarnation$stamp = stamp;
    }

    @Inject(method = "copy()Lnet/minecraft/nbt/IntArrayTag;", at = @At("RETURN"))
    private void reincarnation$copy(CallbackInfoReturnable<IntArrayTag> cir) {
        ReferencePersistence.copyTransient((IntArrayTag) (Object) this, cir.getReturnValue());
    }
}
