package com.iafenvoy.reincarnation.mixin;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ServerPlayer.class)
public interface ServerPlayerResetAccess {
    @Invoker("setShoulderEntityLeft")
    void reincarnation$shoulderLeft(CompoundTag value);
}
