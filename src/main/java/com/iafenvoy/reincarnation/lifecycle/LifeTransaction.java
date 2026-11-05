package com.iafenvoy.reincarnation.lifecycle;

import com.iafenvoy.reincarnation.api.ReincarnationContext;
import com.iafenvoy.reincarnation.api.ReincarnationRequest;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;
import net.minecraft.util.StringRepresentable;
import org.jspecify.annotations.NonNull;

import java.util.Locale;
import java.util.UUID;

public record LifeTransaction(UUID id, UUID account, String name, long from, long to, long createdAt,
                              ReincarnationRequest request, String archiveHash, State state) {
    public static final Codec<LifeTransaction> CODEC = RecordCodecBuilder.create(i -> i.group(
            UUIDUtil.STRING_CODEC.fieldOf("id").forGetter(LifeTransaction::id),
            UUIDUtil.STRING_CODEC.fieldOf("account").forGetter(LifeTransaction::account),
            Codec.STRING.fieldOf("name").forGetter(LifeTransaction::name),
            Codec.LONG.fieldOf("from").forGetter(LifeTransaction::from),
            Codec.LONG.fieldOf("to").forGetter(LifeTransaction::to),
            Codec.LONG.fieldOf("created_at").forGetter(LifeTransaction::createdAt),
            ReincarnationRequest.CODEC.fieldOf("request").forGetter(LifeTransaction::request),
            Codec.STRING.optionalFieldOf("archive_hash", "").forGetter(LifeTransaction::archiveHash),
            State.CODEC.fieldOf("state").forGetter(LifeTransaction::state)
    ).apply(i, LifeTransaction::new));

    public enum State implements StringRepresentable {
        PREPARED, COMMITTING, COMMITTED, ABORTED;
        public static final Codec<State> CODEC = StringRepresentable.fromValues(State::values);

        @Override
        public @NonNull String getSerializedName() {
            return this.name().toLowerCase(Locale.ROOT);
        }
    }

    public LifeTransaction {
        if (from < 0 || from == Long.MAX_VALUE || to != from + 1 || createdAt <= 0)
            throw new IllegalArgumentException("Invalid transaction generations/time");
    }

    public LifeTransaction withState(State state) {
        return new LifeTransaction(this.id, this.account, this.name, this.from, this.to, this.createdAt, this.request, this.archiveHash, state);
    }

    public ReincarnationContext context(boolean recovering) {
        return new ReincarnationContext(this.id, this.account, this.from, this.to, this.request, recovering);
    }
}
