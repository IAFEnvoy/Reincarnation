package com.iafenvoy.reincarnation.mixin;

import com.iafenvoy.reincarnation.lifecycle.ReincarnationService;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.attachment.AttachmentInternals;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@SuppressWarnings("UnstableApiUsage")
@Mixin(AttachmentInternals.class)
public abstract class AttachmentInternalsMixin {
    @Inject(method = "onPlayerClone", at = @At("HEAD"), cancellable = true)
    private static void reincarnation$skipAutomaticCopy(PlayerEvent.Clone event, CallbackInfo ci) {
        if (event.getOriginal() instanceof ServerPlayer old
                && ReincarnationService.get().isInheriting(old.getUUID()))
            ci.cancel();
    }
}
