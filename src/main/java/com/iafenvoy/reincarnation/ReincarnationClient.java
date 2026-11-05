package com.iafenvoy.reincarnation;

import com.iafenvoy.jupiter.render.screen.ConfigSelectScreen;
import com.iafenvoy.reincarnation.config.ReincarnationServerConfig;
import com.iafenvoy.reincarnation.data.information.LifeViewOpenScreenData;
import com.iafenvoy.reincarnation.screen.LifeInformationScreen;
import com.iafenvoy.saturn.screen.opener.ClientScreenDispatcher;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

@EventBusSubscriber(Dist.CLIENT)
public final class ReincarnationClient {
    @SubscribeEvent
    public static void init(FMLClientSetupEvent event) {
        event.getContainer().registerExtensionPoint(IConfigScreenFactory.class, (_, parent) -> ConfigSelectScreen.builder("config.reincarnation.title", parent).server(ReincarnationServerConfig.INSTANCE).build());
        event.enqueueWork(() -> ClientScreenDispatcher.register(LifeViewOpenScreenData.STREAM_CODEC, LifeInformationScreen::open));
    }
}
