package com.iafenvoy.reincarnation.mixin;

import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ServerGamePacketListenerImpl.class)
public interface ConnectionResetAccess {
    @Invoker("restartClientLoadTimerAfterRespawn")
    void reincarnation$restartClientLoadTimer();
}
