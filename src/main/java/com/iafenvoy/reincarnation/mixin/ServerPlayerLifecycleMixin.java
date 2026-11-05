package com.iafenvoy.reincarnation.mixin;

import com.iafenvoy.reincarnation.lifecycle.ReincarnationService;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.EventHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlayer.class)
public abstract class ServerPlayerLifecycleMixin {
    @Inject(method = "restoreFrom", at = @At("HEAD"), cancellable = true)
    private void reincarnation$inherit(ServerPlayer old, boolean restoreAll, CallbackInfo ci) {
        ServerPlayer fresh = (ServerPlayer) (Object) this;
        if (!ReincarnationService.get().inheritFrom(fresh, old)) return;
        EventHooks.onPlayerClone(fresh, old, !restoreAll);
        ci.cancel();
    }
}
