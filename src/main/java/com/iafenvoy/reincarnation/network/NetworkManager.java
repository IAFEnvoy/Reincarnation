package com.iafenvoy.reincarnation.network;

import com.iafenvoy.reincarnation.network.payload.LifeViewRefreshC2SPayload;
import com.iafenvoy.reincarnation.network.payload.LifeViewRefreshS2CPayload;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.MainThreadPayloadHandler;

@EventBusSubscriber
public final class NetworkManager {
    @SubscribeEvent
    public static void registerPayloads(RegisterPayloadHandlersEvent event) {
        event.registrar("1")
                .playToServer(LifeViewRefreshC2SPayload.TYPE, LifeViewRefreshC2SPayload.STREAM_CODEC, new MainThreadPayloadHandler<>(ServerNetworkHandler::onLifeViewRefresh))
                .playToClient(LifeViewRefreshS2CPayload.TYPE, LifeViewRefreshS2CPayload.STREAM_CODEC, new MainThreadPayloadHandler<>(ClientNetworkHandler::onLifeViewRefresh));
    }
}
