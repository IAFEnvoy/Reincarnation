package com.iafenvoy.reincarnation.network;

import com.iafenvoy.reincarnation.network.payload.LifeViewRefreshS2CPayload;
import com.iafenvoy.reincarnation.screen.LifeInformationScreen;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public final class ClientNetworkHandler {
    static void onLifeViewRefresh(LifeViewRefreshS2CPayload payload, IPayloadContext context) {
        LifeInformationScreen.refresh(payload);
    }
}
