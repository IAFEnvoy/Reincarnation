package com.iafenvoy.reincarnation.mixin;

import com.iafenvoy.reincarnation.lifecycle.ReincarnationService;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.storage.PlayerDataStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerDataStorage.class)
public abstract class PlayerDataStorageMixin {
    @Inject(method = "save", at = @At("HEAD"), cancellable = true)
    private void reincarnation$saveGuard(Player player, CallbackInfo ci) {
        if (player instanceof ServerPlayer serverPlayer && !ReincarnationService.get().canSave(serverPlayer))
            ci.cancel();
    }
}
