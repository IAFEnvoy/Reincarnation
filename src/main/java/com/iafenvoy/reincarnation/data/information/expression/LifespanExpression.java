package com.iafenvoy.reincarnation.data.information.expression;

import com.iafenvoy.reincarnation.data.information.getter.LifespanGetter;
import com.iafenvoy.reincarnation.lifespan.Lifespan;
import com.iafenvoy.reincarnation.lifespan.LifespanService;
import com.iafenvoy.reincarnation.config.ReincarnationServerConfig;
import com.iafenvoy.saturn.data.information.expression.*;
import com.iafenvoy.saturn.data.information.getter.EntityValueGetter;
import com.iafenvoy.saturn.data.information.model.InformationValue;
import com.iafenvoy.saturn.data.information.model.MissingReason;
import com.mojang.serialization.MapCodec;
import java.util.List;
import java.util.Set;

public record LifespanExpression() implements InformationExpression {
    public static final MapCodec<LifespanExpression> CODEC = MapCodec.unit(LifespanExpression::new);

    @Override
    public MapCodec<LifespanExpression> codec() {
        return CODEC;
    }

    @Override
    public ExpressionValue evaluate(InformationEvaluation context) {
        Lifespan value = context.getter(LifespanGetter.INSTANCE, List.of()).decode(Lifespan.CODEC);
        if (value.total() < 0) return new ExpressionValue.Missing(MissingReason.NOT_APPLICABLE);
        return new ExpressionValue.Display(new InformationValue.Text(LifespanService.display(value.remaining(), value.total(),
                ReincarnationServerConfig.INSTANCE.lifespan.ticksPerYear.getValue())));
    }

    @Override
    public Set<EntityValueGetter<?>> getters() {
        return Set.of(LifespanGetter.INSTANCE);
    }
}
