package com.iafenvoy.reincarnation.registry;

import com.iafenvoy.reincarnation.Reincarnation;
import com.iafenvoy.reincarnation.data.information.getter.LifeGetter;
import com.iafenvoy.reincarnation.data.information.getter.LifeSourceGetter;
import com.iafenvoy.saturn.data.information.getter.EntityValueGetter;
import com.iafenvoy.saturn.registry.SaturnRegistries;
import com.mojang.serialization.MapCodec;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ReincarnationEntityValueGetters {
    public static final DeferredRegister<MapCodec<? extends EntityValueGetter<?>>> REGISTRY =
            DeferredRegister.create(SaturnRegistries.ENTITY_VALUE_GETTER, Reincarnation.MOD_ID);

    static {
        REGISTRY.register("life", () -> LifeGetter.CODEC);
        REGISTRY.register("life_source", () -> LifeSourceGetter.CODEC);
        REGISTRY.register("lifespan", () -> com.iafenvoy.reincarnation.data.information.getter.LifespanGetter.CODEC);
    }

    private ReincarnationEntityValueGetters() {
    }
}
