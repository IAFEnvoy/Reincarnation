package com.iafenvoy.reincarnation.lifecycle;

import com.iafenvoy.reincarnation.lifespan.LifespanService;
import com.iafenvoy.reincarnation.registry.ReincarnationAttachments;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerRespawnPositionEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

@EventBusSubscriber
public final class ReincarnationEvents {
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onPlayerDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            player.removeData(ReincarnationAttachments.DEATH_REASON);
            player.removeData(ReincarnationAttachments.DEATH_DAMAGE_TYPE);
            event.getSource().typeHolder().unwrapKey().ifPresent(type -> player.setData(ReincarnationAttachments.DEATH_DAMAGE_TYPE, type));
            if (event.getSource().is(LifespanService.DAMAGE_TYPE))
                player.setData(ReincarnationAttachments.DEATH_REASON, Component.translatable("reason.reincarnation.lifespan"));
        }
    }

    @SubscribeEvent
    public static void onServerStart(ServerAboutToStartEvent event) {
        ReincarnationService.start(event.getServer());
    }

    @SubscribeEvent
    public static void onServerStop(ServerStoppedEvent event) {
        ReincarnationService.stop();
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        LifespanService.flush();
        ReincarnationService.get().tick();
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onPlayerRespawn(PlayerRespawnPositionEvent event) {
        if (event.getEntity() instanceof ServerPlayer)
            ReincarnationService.get().respawnPosition(event);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player)
            ReincarnationService.get().login(player);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player)
            ReincarnationService.get().logout(player);
    }

    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.getLevel() instanceof ServerLevel)
            ReincarnationService.get().enqueue(event.getEntity());
    }
}
