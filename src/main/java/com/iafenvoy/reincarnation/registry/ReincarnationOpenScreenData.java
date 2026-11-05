package com.iafenvoy.reincarnation.registry;

import com.iafenvoy.reincarnation.Reincarnation;
import com.iafenvoy.reincarnation.data.information.LifeViewOpenScreenData;
import com.iafenvoy.saturn.registry.SaturnRegistries;
import com.iafenvoy.saturn.screen.opener.OpenScreenData;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

@SuppressWarnings("unused")
public final class ReincarnationOpenScreenData {
    public static final DeferredRegister<StreamCodec<RegistryFriendlyByteBuf, ? extends OpenScreenData>> REGISTRY = DeferredRegister.create(SaturnRegistries.OPEN_SCREEN_DATA, Reincarnation.MOD_ID);

    public static final DeferredHolder<StreamCodec<RegistryFriendlyByteBuf, ? extends OpenScreenData>, StreamCodec<RegistryFriendlyByteBuf, LifeViewOpenScreenData>> LIFE_VIEW = REGISTRY.register("life_view", () -> LifeViewOpenScreenData.STREAM_CODEC);
}
