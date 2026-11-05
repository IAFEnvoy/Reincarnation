package com.iafenvoy.reincarnation.data.lifespan;

import com.iafenvoy.reincarnation.storage.Generations;
import com.iafenvoy.saturn.data.condition.EntityCondition;
import com.iafenvoy.saturn.data.context.condition.EntityConditionContext;
import com.iafenvoy.saturn.util.math.Comparison;
import com.mojang.serialization.MapCodec;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.NotNull;

public record GenerationCondition(Comparison comparison) implements EntityCondition {
    public static final MapCodec<GenerationCondition> CODEC = Comparison.CODEC.xmap(GenerationCondition::new, GenerationCondition::comparison);

    @Override
    public boolean test(@NotNull EntityConditionContext context) {
        return context.entity() instanceof ServerPlayer player && this.comparison.compare(
                (double) Generations.current(player.level().getServer(), player.getUUID()) + 1);
    }

    @Override
    public @NotNull MapCodec<GenerationCondition> codec() {
        return CODEC;
    }
}
