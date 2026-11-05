package com.iafenvoy.reincarnation.api;

import com.iafenvoy.reincarnation.config.ReincarnationServerConfig;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;

public record ReincarnationRequest(ResourceKey<Level> dimension, @NotNull Vec3 position, float yaw, float pitch,
                                   @NotNull Component reason, @NotNull ResetPolicy policy) {
    public static final Codec<ReincarnationRequest> CODEC = RecordCodecBuilder.create(i -> i.group(
            ResourceKey.codec(Registries.DIMENSION).fieldOf("dimension").forGetter(ReincarnationRequest::dimension),
            Vec3.CODEC.fieldOf("position").forGetter(ReincarnationRequest::position),
            Codec.FLOAT.fieldOf("yaw").forGetter(ReincarnationRequest::yaw),
            Codec.FLOAT.fieldOf("pitch").forGetter(ReincarnationRequest::pitch),
            ComponentSerialization.CODEC.fieldOf("reason").forGetter(ReincarnationRequest::reason),
            ResetPolicy.CODEC.fieldOf("policy").forGetter(ReincarnationRequest::policy)
    ).apply(i, ReincarnationRequest::new));

    public ReincarnationRequest {
        reason = reason.copy();
        if (!Double.isFinite(position.x) || !Double.isFinite(position.y) || !Double.isFinite(position.z) || !Float.isFinite(yaw) || !Float.isFinite(pitch))
            throw new IllegalArgumentException("Invalid reincarnation request");
    }

    public static ReincarnationRequest atSpawn(ServerPlayer player, Component reason) {
        TeleportTransition spawn = TeleportTransition.createDefault(player, TeleportTransition.DO_NOTHING);
        return new ReincarnationRequest(spawn.newLevel().dimension(), spawn.position(), spawn.yRot(), spawn.xRot(), reason, ReincarnationServerConfig.INSTANCE.resetPolicy());
    }
}
