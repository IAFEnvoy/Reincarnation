package com.iafenvoy.reincarnation.mixin;

import com.iafenvoy.reincarnation.lifecycle.ReincarnationService;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;
import java.util.Set;

@Mixin(PlayerList.class)
public abstract class PlayerListLifecycleMixin {
    @Inject(method = "respawn", at = @At("HEAD"), cancellable = true)
    private void reincarnation$automaticRespawn(ServerPlayer serverPlayer, boolean keepAllPlayerData, Entity.RemovalReason removalReason, CallbackInfoReturnable<ServerPlayer> cir) {
        ReincarnationService.get().automaticRespawn(serverPlayer, keepAllPlayerData).ifPresent(cir::setReturnValue);
    }

    @ModifyExpressionValue(method = "respawn", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;entityTags()Ljava/util/Set;"))
    private Set<String> reincarnation$skipOldTags(Set<String> tags, ServerPlayer old) {
        return ReincarnationService.get().isInheriting(old.getUUID()) ? Set.of() : tags;
    }

    @Inject(method = "loadPlayerData", at = @At("HEAD"), cancellable = true)
    private void reincarnation$pendingLoad(NameAndId nameAndId, CallbackInfoReturnable<Optional<CompoundTag>> cir) {
        if (ReincarnationService.get().emptyLoad(nameAndId.id())) cir.setReturnValue(Optional.empty());
    }

    @Inject(method = "loadPlayerData", at = @At("RETURN"))
    private void reincarnation$validateLoad(NameAndId nameAndId, CallbackInfoReturnable<Optional<CompoundTag>> cir) {
        ReincarnationService.get().validateLoad(nameAndId.id(), cir.getReturnValue());
    }

    @Inject(method = "save", at = @At("HEAD"), cancellable = true)
    private void reincarnation$saveGuard(ServerPlayer player, CallbackInfo ci) {
        if (!ReincarnationService.get().canSave(player)) ci.cancel();
    }

    @Inject(method = "save", at = @At("RETURN"))
    private void reincarnation$observeSavedLife(ServerPlayer player, CallbackInfo ci) {
        ReincarnationService.get().observe(player);
    }
}
