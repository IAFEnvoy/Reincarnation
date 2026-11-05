package com.iafenvoy.reincarnation.data.information.getter;

import com.iafenvoy.reincarnation.api.LifespanApi;
import com.iafenvoy.reincarnation.lifespan.Lifespan;
import com.iafenvoy.saturn.data.information.getter.EntityValueGetter;
import com.iafenvoy.saturn.data.information.model.MissingReason;
import com.iafenvoy.saturn.data.information.model.SavedPlayerViewContext;
import com.iafenvoy.saturn.data.information.model.ValueOrigin;
import com.iafenvoy.saturn.data.information.model.ViewValue;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.Entity;

public enum LifespanGetter implements EntityValueGetter<Lifespan> {
    INSTANCE;
    public static final MapCodec<LifespanGetter> CODEC = MapCodec.unit(INSTANCE);

    @Override
    public MapCodec<LifespanGetter> codec() {
        return CODEC;
    }

    @Override
    public Codec<Lifespan> valueCodec() {
        return Lifespan.CODEC;
    }

    @Override
    public ViewValue<Lifespan> capture(Entity entity) {
        return ViewValue.known(LifespanApi.get(entity), ValueOrigin.CAPTURED);
    }

    @Override
    public ViewValue<Lifespan> readSaved(SavedPlayerViewContext context) {
        CompoundTag attachments = context.player().getCompound("neoforge:attachments").orElseGet(CompoundTag::new);
        Tag tag = attachments.get("reincarnation:lifespan");
        if (tag == null) tag = attachments.get("mxt:lifespan");
        if (tag == null) return ViewValue.missing(MissingReason.NOT_CAPTURED);
        return Lifespan.CODEC.parse(NbtOps.INSTANCE, tag).result()
                .map(value -> ViewValue.known(value, ValueOrigin.SAVED))
                .orElseGet(() -> ViewValue.missing(MissingReason.INVALID));
    }
}
