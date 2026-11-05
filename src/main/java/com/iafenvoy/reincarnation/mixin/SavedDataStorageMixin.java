package com.iafenvoy.reincarnation.mixin;

import com.iafenvoy.reincarnation.reference.ReferencePersistence;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.world.level.storage.SavedDataStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SavedDataStorage.class)
public abstract class SavedDataStorageMixin {
    @Inject(method = "encodeUnchecked", at = @At("RETURN"), cancellable = true)
    private void reincarnation$pack(CallbackInfoReturnable<CompoundTag> cir) {
        cir.setReturnValue((CompoundTag) ReferencePersistence.pack(NbtOps.INSTANCE, cir.getReturnValue()));
    }

    // readTagFromDisk returns AFTER the data fixer. NbtIo also restores before conversion.
    @Inject(method = "readTagFromDisk", at = @At("RETURN"), cancellable = true)
    private void reincarnation$restore(CallbackInfoReturnable<CompoundTag> cir) {
        cir.setReturnValue((CompoundTag) ReferencePersistence.restore(NbtOps.INSTANCE, cir.getReturnValue()));
    }
}
