package com.iafenvoy.reincarnation.data.lifespan;

import com.iafenvoy.reincarnation.api.LifespanApi;
import com.iafenvoy.reincarnation.api.RebirthMode;
import com.iafenvoy.saturn.data.action.EntityAction;
import com.iafenvoy.saturn.data.context.action.EntityActionContext;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.world.entity.LivingEntity;
import org.jetbrains.annotations.NotNull;

public record ReincarnateAction(RebirthMode mode, Component reason) implements EntityAction {
    public static final MapCodec<ReincarnateAction> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            RebirthMode.CODEC.optionalFieldOf("mode", RebirthMode.CONFIGURED).forGetter(ReincarnateAction::mode),
            ComponentSerialization.CODEC.optionalFieldOf("reason", Component.translatable("reason.reincarnation.rebirth")).forGetter(ReincarnateAction::reason)
    ).apply(i, ReincarnateAction::new));

    @Override
    public void execute(@NotNull EntityActionContext context) {
        if (context.entity() instanceof LivingEntity living) LifespanApi.requestRebirth(living, this.mode, this.reason);
    }

    @Override
    public @NotNull MapCodec<ReincarnateAction> codec() {
        return CODEC;
    }
}
