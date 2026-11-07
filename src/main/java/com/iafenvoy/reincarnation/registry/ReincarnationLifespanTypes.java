package com.iafenvoy.reincarnation.registry;

import com.iafenvoy.reincarnation.Reincarnation;
import com.iafenvoy.reincarnation.data.lifespan.*;
import com.iafenvoy.saturn.data.action.EntityAction;
import com.iafenvoy.saturn.data.condition.EntityCondition;
import com.iafenvoy.saturn.registry.SaturnRegistries;
import com.iafenvoy.saturn.util.formula.FormulaVariable;
import com.mojang.serialization.MapCodec;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ReincarnationLifespanTypes {
    public static final DeferredRegister<MapCodec<? extends EntityAction>> ACTIONS = DeferredRegister.create(SaturnRegistries.ENTITY_ACTION_TYPE, Reincarnation.MOD_ID);
    public static final DeferredRegister<MapCodec<? extends EntityCondition>> CONDITIONS = DeferredRegister.create(SaturnRegistries.ENTITY_CONDITION_TYPE, Reincarnation.MOD_ID);
    public static final DeferredRegister<FormulaVariable> VARIABLES = DeferredRegister.create(SaturnRegistries.FORMULA_VARIABLE, Reincarnation.MOD_ID);

    static {
        ACTIONS.register("add_lifespan", () -> AddLifespanAction.CODEC);
        ACTIONS.register("remove_lifespan", () -> RemoveLifespanAction.CODEC);
        ACTIONS.register("reincarnate", () -> ReincarnateAction.CODEC);
        ACTIONS.register("unlimited_lifespan", () -> UnlimitedLifespanAction.CODEC);
        CONDITIONS.register("lifespan", () -> LifespanCondition.CODEC);
        CONDITIONS.register("lifespan_state", () -> LifespanStateCondition.CODEC);
        CONDITIONS.register("generation", () -> GenerationCondition.CODEC);
        VARIABLES.register("lifespan", LifespanVariable::new);
    }

    private ReincarnationLifespanTypes() {
    }
}
