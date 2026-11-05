package com.iafenvoy.reincarnation;

import com.iafenvoy.jupiter.ConfigManager;
import com.iafenvoy.jupiter.ServerConfigManager.PermissionChecker;
import com.iafenvoy.reincarnation.config.ReincarnationServerConfig;
import com.iafenvoy.reincarnation.gametest.ReferenceTests;
import com.iafenvoy.reincarnation.lifespan.LifespanEvents;
import com.iafenvoy.reincarnation.registry.*;
import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;

@Mod(Reincarnation.MOD_ID)
public class Reincarnation {
    public static final String MOD_ID = "reincarnation";
    public static final Logger LOGGER = LogUtils.getLogger();

    public Reincarnation(IEventBus bus) {
        ConfigManager.getInstance().registerServerConfigHandler(ReincarnationServerConfig.INSTANCE, PermissionChecker.IS_OPERATOR);

        ReincarnationEntityValueGetters.REGISTRY.register(bus);
        ReincarnationInformationExpressions.REGISTRY.register(bus);
        ReincarnationOpenScreenData.REGISTRY.register(bus);
        ReincarnationAttachments.REGISTRY.register(bus);
        ReincarnationAttachments.LEGACY_REGISTRY.register(bus);
        ReincarnationLifespanTypes.ACTIONS.register(bus);
        ReincarnationLifespanTypes.CONDITIONS.register(bus);
        ReincarnationLifespanTypes.VARIABLES.register(bus);
        bus.addListener(LifespanEvents::registerHandler);

        ReferenceTests.register(bus);
    }
}
