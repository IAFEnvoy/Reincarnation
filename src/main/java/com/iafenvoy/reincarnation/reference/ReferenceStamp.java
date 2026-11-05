package com.iafenvoy.reincarnation.reference;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;

import java.util.UUID;

public record ReferenceStamp(UUID target, long generation) {
    public static final MapCodec<ReferenceStamp> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            UUIDUtil.CODEC.fieldOf("target").forGetter(ReferenceStamp::target),
            Codec.LONG.validate(value -> value >= 0 ? DataResult.success(value)
                    : DataResult.error(() -> "Negative reference generation")).fieldOf("generation").forGetter(ReferenceStamp::generation)
    ).apply(i, ReferenceStamp::new));

    public ReferenceStamp {
        if (target == null || generation < 0) throw new IllegalArgumentException("Invalid reference stamp");
    }
}
