package com.iafenvoy.reincarnation.mixin;

import com.iafenvoy.reincarnation.reference.ReferencePersistence;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.DataInput;

@Mixin(NbtIo.class)
public abstract class NbtIoMixin {
    @ModifyVariable(method = {
            "writeUnnamedTag(Lnet/minecraft/nbt/Tag;Ljava/io/DataOutput;)V",
            "writeAnyTag(Lnet/minecraft/nbt/Tag;Ljava/io/DataOutput;)V"
    }, at = @At("HEAD"), argsOnly = true, name = "tag")
    private static Tag reincarnation$snapshot(Tag tag) {
        return ReferencePersistence.snapshotForWrite(NbtOps.INSTANCE, tag);
    }

    @Inject(method = {
            "readUnnamedTag(Ljava/io/DataInput;Lnet/minecraft/nbt/NbtAccounter;)Lnet/minecraft/nbt/Tag;",
            "readAnyTag(Ljava/io/DataInput;Lnet/minecraft/nbt/NbtAccounter;)Lnet/minecraft/nbt/Tag;"
    }, at = @At("RETURN"), cancellable = true)
    private static void reincarnation$restore(DataInput input, NbtAccounter accounter, CallbackInfoReturnable<Tag> cir) {
        cir.setReturnValue(ReferencePersistence.restore(NbtOps.INSTANCE, cir.getReturnValue()));
    }
}
