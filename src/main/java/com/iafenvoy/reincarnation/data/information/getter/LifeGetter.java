package com.iafenvoy.reincarnation.data.information.getter;

import com.iafenvoy.reincarnation.lifecycle.LifeMarker;
import com.iafenvoy.reincarnation.lifecycle.SoulRecord;
import com.iafenvoy.saturn.data.information.getter.EntityValueGetter;
import com.iafenvoy.saturn.data.information.model.MissingReason;
import com.iafenvoy.saturn.data.information.model.ValueOrigin;
import com.iafenvoy.saturn.data.information.model.ViewValue;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

public enum LifeGetter implements EntityValueGetter<SoulRecord> {
    INSTANCE;

    public static final MapCodec<LifeGetter> CODEC = MapCodec.unit(INSTANCE);

    @Override
    public MapCodec<LifeGetter> codec() {
        return CODEC;
    }

    @Override
    public Codec<SoulRecord> valueCodec() {
        return SoulRecord.CODEC;
    }

    @Override
    public ViewValue<SoulRecord> capture(Entity entity) {
        if (!(entity instanceof ServerPlayer player)) return ViewValue.missing(MissingReason.UNSUPPORTED);
        CompoundTag marker = LifeMarker.of(player);
        if (!marker.contains("generation")) return ViewValue.missing(MissingReason.NOT_CAPTURED);
        return ViewValue.known(SoulRecord.capture(player, marker.getLong("generation").orElseThrow(),
                marker.getLong("started").orElse(0L), 0, Component.empty(), marker.getString("transaction").orElse("")), ValueOrigin.CAPTURED);
    }
}
