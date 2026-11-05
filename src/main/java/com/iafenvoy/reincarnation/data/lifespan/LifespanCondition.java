package com.iafenvoy.reincarnation.data.lifespan;

import com.iafenvoy.reincarnation.api.LifespanApi;
import com.iafenvoy.saturn.data.condition.EntityCondition;
import com.iafenvoy.saturn.data.context.condition.EntityConditionContext;
import com.iafenvoy.saturn.util.math.Comparison;
import com.mojang.serialization.*;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.entity.LivingEntity;
import org.jetbrains.annotations.NotNull;
import java.util.Locale;

public record LifespanCondition(Field field, Comparison comparison) implements EntityCondition {
    public static final MapCodec<LifespanCondition> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Field.CODEC.optionalFieldOf("field", Field.REMAINING).forGetter(LifespanCondition::field),
            Comparison.CODEC.forGetter(LifespanCondition::comparison)
    ).apply(i, LifespanCondition::new));

    @Override
    public boolean test(@NotNull EntityConditionContext context) {
        if (!(context.entity() instanceof LivingEntity)) return false;
        var value = LifespanApi.get(context.entity());
        if (!value.finite()) return false;
        return this.comparison.compare(switch (this.field) {
            case REMAINING -> value.remaining();
            case TOTAL -> value.total();
            case FRACTION -> value.total() == 0 ? 0 : (double) value.remaining() / value.total();
            case SPENT -> value.total() - value.remaining();
        });
    }

    @Override
    public @NotNull MapCodec<LifespanCondition> codec() {
        return CODEC;
    }

    public enum Field implements StringRepresentable {
        REMAINING, TOTAL, FRACTION, SPENT;
        public static final Codec<Field> CODEC = StringRepresentable.fromEnum(Field::values);
        @Override
        public @NotNull String getSerializedName() {
            return this.name().toLowerCase(Locale.ROOT);
        }
    }
}
