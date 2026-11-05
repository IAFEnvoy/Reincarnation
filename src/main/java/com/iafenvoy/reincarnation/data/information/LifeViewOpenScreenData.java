package com.iafenvoy.reincarnation.data.information;

import com.iafenvoy.reincarnation.api.ReincarnationException;
import com.iafenvoy.saturn.data.information.model.InformationDocument;
import com.iafenvoy.saturn.screen.opener.OpenScreenData;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

import java.util.List;
import java.util.UUID;

public record LifeViewOpenScreenData(UUID account, String name, long generation, List<Life> lives,
                                     int page, int pages, boolean current,
                                     InformationDocument document) implements OpenScreenData {
    public static final int PAGE_SIZE = 32;
    public static final int MAX_BYTES = 1_000_000;
    private static final StreamCodec<RegistryFriendlyByteBuf, LifeViewOpenScreenData> CONTENT_CODEC = StreamCodec.composite(
            UUIDUtil.STREAM_CODEC, LifeViewOpenScreenData::account,
            ByteBufCodecs.stringUtf8(256), LifeViewOpenScreenData::name,
            ByteBufCodecs.VAR_LONG, LifeViewOpenScreenData::generation,
            Life.STREAM_CODEC.apply(ByteBufCodecs.list(PAGE_SIZE)), LifeViewOpenScreenData::lives,
            ByteBufCodecs.VAR_INT, LifeViewOpenScreenData::page,
            ByteBufCodecs.VAR_INT, LifeViewOpenScreenData::pages,
            ByteBufCodecs.BOOL, LifeViewOpenScreenData::current,
            ByteBufCodecs.fromCodecWithRegistries(InformationDocument.CODEC), LifeViewOpenScreenData::document,
            LifeViewOpenScreenData::new);
    public static final StreamCodec<RegistryFriendlyByteBuf, LifeViewOpenScreenData> STREAM_CODEC = CONTENT_CODEC
            .apply(ByteBufCodecs.registryFriendlyLengthPrefixed(MAX_BYTES));

    public LifeViewOpenScreenData {
        lives = List.copyOf(lives);
        if (generation < 0 || page < 1 || pages < page || lives.isEmpty() || lives.size() > PAGE_SIZE)
            throw new IllegalArgumentException("Invalid life view page");
    }

    public void validateSize(RegistryAccess registries) {
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), registries);
        try {
            CONTENT_CODEC.encode(buffer, this);
            if (buffer.readableBytes() > MAX_BYTES)
                throw new ReincarnationException("error.reincarnation.view_too_large");
        } finally {
            buffer.release();
        }
    }

    public record Life(long generation, long endedAt, Component reason) {
        public static final StreamCodec<RegistryFriendlyByteBuf, Life> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_LONG, Life::generation,
                ByteBufCodecs.VAR_LONG, Life::endedAt,
                ComponentSerialization.STREAM_CODEC, Life::reason,
                Life::new);

        public Life {
            if (generation < 0) throw new IllegalArgumentException("Negative life generation");
            reason = reason.copy();
        }
    }

    @Override
    public StreamCodec<RegistryFriendlyByteBuf, ? extends OpenScreenData> streamCodec() {
        return STREAM_CODEC;
    }
}
