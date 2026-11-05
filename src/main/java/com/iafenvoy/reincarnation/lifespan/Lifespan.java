package com.iafenvoy.reincarnation.lifespan;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

public record Lifespan(long remaining, long total) {
    public static final Lifespan UNACCOUNTED = new Lifespan(-1, -1);
    public static final Lifespan CLOSED = new Lifespan(-1, 0);
    private static final Codec<Long> TICKS = Codec.LONG.validate(value -> value >= -1
            ? DataResult.success(value) : DataResult.error(() -> "Lifespan ticks must be at least -1"));
    public static final MapCodec<Lifespan> MAP_CODEC = RecordCodecBuilder.<Lifespan>mapCodec(i -> i.group(
            TICKS.optionalFieldOf("lifespan_remaining", -1L).forGetter(Lifespan::remaining),
            TICKS.optionalFieldOf("lifespan_total", -1L).forGetter(Lifespan::total)
    ).apply(i, Lifespan::new)).validate(value -> value.remaining > value.total
            ? DataResult.error(() -> "Lifespan remaining exceeds total") : DataResult.success(value));
    public static final Codec<Lifespan> CODEC = MAP_CODEC.codec();

    public boolean finite() {
        return this.remaining >= 0;
    }

    public boolean expired() {
        return this.remaining == 0;
    }
}
