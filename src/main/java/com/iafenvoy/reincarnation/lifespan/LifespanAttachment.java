package com.iafenvoy.reincarnation.lifespan;

import com.mojang.serialization.MapCodec;

public final class LifespanAttachment {
    public static final MapCodec<LifespanAttachment> CODEC = Lifespan.MAP_CODEC.xmap(LifespanAttachment::new, LifespanAttachment::value);
    private Lifespan value;
    // Session-only state; loading an already-low ledger must still allow a warning.
    boolean warningSent;

    public LifespanAttachment() {
        this(Lifespan.UNACCOUNTED);
    }

    public LifespanAttachment(Lifespan value) {
        this.value = value;
    }

    public Lifespan value() {
        return this.value;
    }

    public long lifespanRemaining() {
        return this.value.remaining();
    }

    public long lifespanTotal() {
        return this.value.total();
    }

    public void setLifespan(long remaining, long total) {
        if (remaining < -1 || total < -1 || remaining > total)
            throw new IllegalArgumentException("Invalid lifespan ledger");
        this.value = new Lifespan(remaining, total);
    }
}
