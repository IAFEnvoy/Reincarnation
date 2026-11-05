package com.iafenvoy.reincarnation.mixin;

import com.iafenvoy.reincarnation.reference.ReferencePersistence;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.world.level.storage.TagValueOutput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(TagValueOutput.class)
public abstract class TagValueOutputMixin {
    @Inject(method = "buildResult", at = @At("RETURN"), cancellable = true)
    private void reincarnation$pack(CallbackInfoReturnable<CompoundTag> cir) {
        // Raw NBT fields can alias live entity/mod data. Never add payload metadata into that data.
        cir.setReturnValue((CompoundTag) ReferencePersistence.pack(NbtOps.INSTANCE, cir.getReturnValue().copy()));
    }
}
