package com.iafenvoy.reincarnation.data.information.getter;

import com.iafenvoy.saturn.api.playerview.PlayerView;
import com.iafenvoy.saturn.data.information.getter.EntityValueGetter;
import com.iafenvoy.saturn.data.information.model.PlayerViewMeta;
import com.iafenvoy.saturn.data.information.model.SavedPlayerViewContext;
import com.iafenvoy.saturn.data.information.model.ValueOrigin;
import com.iafenvoy.saturn.data.information.model.ViewValue;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import net.minecraft.world.entity.Entity;

public enum LifeSourceGetter implements EntityValueGetter<PlayerViewMeta.SourceKind> {
    INSTANCE;

    public static final MapCodec<LifeSourceGetter> CODEC = MapCodec.unit(INSTANCE);

    @Override
    public MapCodec<LifeSourceGetter> codec() {
        return CODEC;
    }

    @Override
    public Codec<PlayerViewMeta.SourceKind> valueCodec() {
        return PlayerViewMeta.SourceKind.CODEC;
    }

    @Override
    public ViewValue<PlayerViewMeta.SourceKind> capture(Entity entity) {
        return ViewValue.known(PlayerViewMeta.SourceKind.LIVE, ValueOrigin.CAPTURED);
    }

    @Override
    public ViewValue<PlayerViewMeta.SourceKind> readSaved(SavedPlayerViewContext context) {
        return ViewValue.known(context.meta().sourceKind(), ValueOrigin.SAVED);
    }

    @Override
    public ViewValue<PlayerViewMeta.SourceKind> fallback(PlayerView view) {
        return ViewValue.known(view.meta().sourceKind(), ValueOrigin.RECONSTRUCTED);
    }
}
