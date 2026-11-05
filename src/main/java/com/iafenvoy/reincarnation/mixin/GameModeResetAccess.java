package com.iafenvoy.reincarnation.mixin;

import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.level.GameType;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ServerPlayerGameMode.class)
public interface GameModeResetAccess {
    @Invoker("setGameModeForPlayer")
    void reincarnation$mode(GameType mode, @Nullable GameType previous);
}
