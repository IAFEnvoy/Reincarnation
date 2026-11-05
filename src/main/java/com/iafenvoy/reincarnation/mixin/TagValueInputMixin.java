package com.iafenvoy.reincarnation.mixin;

import com.iafenvoy.reincarnation.reference.ReferencePersistence;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.world.level.storage.TagValueInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.List;

@Mixin(TagValueInput.class)
public abstract class TagValueInputMixin {
    @ModifyVariable(method = "create(Lnet/minecraft/util/ProblemReporter;Lnet/minecraft/core/HolderLookup$Provider;Lnet/minecraft/nbt/CompoundTag;)Lnet/minecraft/world/level/storage/ValueInput;", at = @At("HEAD"), argsOnly = true)
    private static CompoundTag reincarnation$restore(CompoundTag tag) {
        return (CompoundTag) ReferencePersistence.restore(NbtOps.INSTANCE, tag);
    }

    @ModifyVariable(method = "create(Lnet/minecraft/util/ProblemReporter;Lnet/minecraft/core/HolderLookup$Provider;Ljava/util/List;)Lnet/minecraft/world/level/storage/ValueInput$ValueInputList;", at = @At("HEAD"), argsOnly = true)
    private static List<CompoundTag> reincarnation$restoreList(List<CompoundTag> tags) {
        return tags.stream().map(tag -> (CompoundTag) ReferencePersistence.restore(NbtOps.INSTANCE, tag)).toList();
    }
}
