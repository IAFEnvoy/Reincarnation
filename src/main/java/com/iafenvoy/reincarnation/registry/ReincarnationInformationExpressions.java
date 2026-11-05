package com.iafenvoy.reincarnation.registry;

import com.iafenvoy.reincarnation.Reincarnation;
import com.iafenvoy.reincarnation.data.information.expression.LifeExpression;
import com.iafenvoy.saturn.data.information.expression.InformationExpression;
import com.iafenvoy.saturn.registry.SaturnRegistries;
import com.mojang.serialization.MapCodec;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ReincarnationInformationExpressions {
    public static final DeferredRegister<MapCodec<? extends InformationExpression>> REGISTRY =
            DeferredRegister.create(SaturnRegistries.INFORMATION_EXPRESSION_TYPE, Reincarnation.MOD_ID);

    static {
        REGISTRY.register("life", () -> LifeExpression.CODEC);
        REGISTRY.register("lifespan", () -> com.iafenvoy.reincarnation.data.information.expression.LifespanExpression.CODEC);
    }

    private ReincarnationInformationExpressions() {
    }
}
