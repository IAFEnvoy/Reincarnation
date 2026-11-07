package com.iafenvoy.reincarnation.reference;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;
import net.minecraft.util.ExtraCodecs;

import java.util.UUID;

public record ReferenceStamp(UUID target, long generation) {
    public static final MapCodec<ReferenceStamp> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            UUIDUtil.CODEC.fieldOf("target").forGetter(ReferenceStamp::target),
            ExtraCodecs.NON_NEGATIVE_LONG.fieldOf("generation").forGetter(ReferenceStamp::generation)
    ).apply(i, ReferenceStamp::new));

    public ReferenceStamp {
        if (target == null || generation < 0) throw new IllegalArgumentException("Invalid reference stamp");
    }
}
