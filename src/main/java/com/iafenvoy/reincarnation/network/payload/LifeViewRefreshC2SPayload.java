package com.iafenvoy.reincarnation.network.payload;

import com.iafenvoy.reincarnation.Reincarnation;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.NonNull;

import java.util.UUID;

public record LifeViewRefreshC2SPayload(UUID requestId, UUID account, long generation) implements CustomPacketPayload {
    public static final Type<LifeViewRefreshC2SPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(Reincarnation.MOD_ID, "life_view_refresh_request"));
    public static final StreamCodec<RegistryFriendlyByteBuf, LifeViewRefreshC2SPayload> STREAM_CODEC = StreamCodec.composite(
            UUIDUtil.STREAM_CODEC, LifeViewRefreshC2SPayload::requestId,
            UUIDUtil.STREAM_CODEC, LifeViewRefreshC2SPayload::account,
            ByteBufCodecs.VAR_LONG, LifeViewRefreshC2SPayload::generation,
            LifeViewRefreshC2SPayload::new);

    public LifeViewRefreshC2SPayload {
        if (generation < 0) throw new IllegalArgumentException("Negative generation");
    }

    @Override
    public @NonNull Type<LifeViewRefreshC2SPayload> type() {
        return TYPE;
    }
}
