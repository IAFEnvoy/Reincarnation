package com.iafenvoy.reincarnation.data.lifespan;

import com.iafenvoy.reincarnation.api.LifespanApi;
import com.iafenvoy.reincarnation.lifespan.Lifespan;
import com.iafenvoy.saturn.data.condition.EntityCondition;
import com.iafenvoy.saturn.data.context.condition.EntityConditionContext;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.entity.LivingEntity;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;

public record LifespanStateCondition(State state) implements EntityCondition {
    public static final MapCodec<LifespanStateCondition> CODEC = State.CODEC.fieldOf("state").xmap(LifespanStateCondition::new, LifespanStateCondition::state);

    @Override
    public boolean test(@NotNull EntityConditionContext context) {
        if (!(context.entity() instanceof LivingEntity)) return false;
        Lifespan value = LifespanApi.get(context.entity());
        return switch (this.state) {
            case FINITE -> value.finite();
            case UNLIMITED -> !value.finite();
            case UNACCOUNTED -> value.total() < 0;
            case EXPIRED -> value.expired();
        };
    }

    @Override
    public @NotNull MapCodec<LifespanStateCondition> codec() {
        return CODEC;
    }

    public enum State implements StringRepresentable {
        FINITE, UNLIMITED, UNACCOUNTED, EXPIRED;
        public static final Codec<State> CODEC = StringRepresentable.fromEnum(State::values);

        @Override
        public @NotNull String getSerializedName() {
            return this.name().toLowerCase(Locale.ROOT);
        }
    }
}
