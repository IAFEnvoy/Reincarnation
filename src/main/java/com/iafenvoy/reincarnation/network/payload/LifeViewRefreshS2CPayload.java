package com.iafenvoy.reincarnation.network.payload;

import com.iafenvoy.reincarnation.Reincarnation;
import com.iafenvoy.reincarnation.data.information.LifeViewOpenScreenData;
import com.iafenvoy.saturn.data.information.model.InformationDocument;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.NonNull;

import java.util.Optional;
import java.util.UUID;

public record LifeViewRefreshS2CPayload(UUID requestId, UUID account, long generation,
                                        Optional<LifeViewOpenScreenData.Life> life,
                                        Optional<InformationDocument> document) implements CustomPacketPayload {
    public static final Type<LifeViewRefreshS2CPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(Reincarnation.MOD_ID, "life_view_refresh_update"));
    public static final StreamCodec<RegistryFriendlyByteBuf, LifeViewRefreshS2CPayload> STREAM_CODEC = StreamCodec.composite(
                    UUIDUtil.STREAM_CODEC, LifeViewRefreshS2CPayload::requestId,
                    UUIDUtil.STREAM_CODEC, LifeViewRefreshS2CPayload::account,
                    ByteBufCodecs.VAR_LONG, LifeViewRefreshS2CPayload::generation,
                    ByteBufCodecs.optional(LifeViewOpenScreenData.Life.STREAM_CODEC), LifeViewRefreshS2CPayload::life,
                    ByteBufCodecs.optional(ByteBufCodecs.fromCodecWithRegistries(InformationDocument.CODEC)), LifeViewRefreshS2CPayload::document,
                    LifeViewRefreshS2CPayload::new)
            .apply(ByteBufCodecs.registryFriendlyLengthPrefixed(LifeViewOpenScreenData.MAX_BYTES));

    public boolean current() {
        return this.life.filter(value -> value.endedAt() == 0).isPresent() && this.document.isPresent();
    }

    @Override
    public @NonNull Type<LifeViewRefreshS2CPayload> type() {
        return TYPE;
    }
}
