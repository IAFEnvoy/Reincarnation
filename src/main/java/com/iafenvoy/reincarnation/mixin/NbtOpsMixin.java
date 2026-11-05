package com.iafenvoy.reincarnation.mixin;

import com.iafenvoy.reincarnation.reference.ReferencePersistence;
import com.mojang.serialization.DynamicOps;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(NbtOps.class)
public abstract class NbtOpsMixin {
    @Inject(method = "convertTo(Lcom/mojang/serialization/DynamicOps;Lnet/minecraft/nbt/Tag;)Ljava/lang/Object;", at = @At("RETURN"))
    private <T> void reincarnation$convert(DynamicOps<T> outOps, Tag input, CallbackInfoReturnable<T> cir) {
        if (cir.getReturnValue() instanceof Tag result) ReferencePersistence.copyTransient(input, result);
    }
}
