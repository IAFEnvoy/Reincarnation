package com.iafenvoy.reincarnation.data.lifespan;

import com.iafenvoy.reincarnation.api.LifespanApi;
import com.iafenvoy.saturn.data.action.EntityAction;
import com.iafenvoy.saturn.data.context.action.EntityActionContext;
import com.mojang.serialization.MapCodec;
import net.minecraft.world.entity.LivingEntity;
import org.jetbrains.annotations.NotNull;

public record UnlimitedLifespanAction() implements EntityAction {
    public static final MapCodec<UnlimitedLifespanAction> CODEC = MapCodec.unit(UnlimitedLifespanAction::new);

    @Override
    public void execute(@NotNull EntityActionContext context) {
        if (context.entity() instanceof LivingEntity living) LifespanApi.unlimited(living);
    }

    @Override
    public @NotNull MapCodec<UnlimitedLifespanAction> codec() {
        return CODEC;
    }
}
