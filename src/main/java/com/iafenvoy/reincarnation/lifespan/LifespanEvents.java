package com.iafenvoy.reincarnation.lifespan;

import com.iafenvoy.reincarnation.Reincarnation;
import com.iafenvoy.reincarnation.api.ReincarnationContext;
import com.iafenvoy.reincarnation.api.ReincarnationHandler;
import com.iafenvoy.reincarnation.config.ReincarnationServerConfig;
import com.iafenvoy.reincarnation.event.RegisterReincarnationHandlersEvent;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

@EventBusSubscriber
public final class LifespanEvents {
    private LifespanEvents() {
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void tick(EntityTickEvent.Post event) {
        Entity entity = event.getEntity();
        ReincarnationServerConfig.Lifespan settings = ReincarnationServerConfig.INSTANCE.lifespan;
        if (!entity.level().isClientSide() && settings.enabled.getValue()
                && entity.level().getGameTime() % settings.settleInterval.getValue() == 0)
            LifespanService.settle(entity);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void login(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            LifespanService.migrate(player);
            LifespanService.seed(player);
            LifespanService.warn(player);
        }
    }

    @SubscribeEvent
    public static void join(EntityJoinLevelEvent event) {
        if (!event.getLevel().isClientSide()) LifespanService.migrate(event.getEntity());
    }

    @SubscribeEvent
    public static void stop(ServerStoppedEvent event) {
        LifespanService.clear();
    }

    public static void registerHandler(RegisterReincarnationHandlersEvent event) {
        event.register(new ReincarnationHandler() {
            @Override
            public Identifier id() {
                return Identifier.fromNamespaceAndPath(Reincarnation.MOD_ID, "lifespan");
            }

            @Override
            public int priority() {
                return 1000;
            }

            @Override
            public CompoundTag archive(ServerPlayer player) {
                ReincarnationServerConfig.Lifespan settings = ReincarnationServerConfig.INSTANCE.lifespan;
                long base = settings.baseLifespan.getValue();
                Lifespan initial = !settings.enabled.getValue() ? Lifespan.UNACCOUNTED
                        : base > 0 ? new Lifespan(base, base) : Lifespan.CLOSED;
                return (CompoundTag) Lifespan.CODEC.encodeStart(NbtOps.INSTANCE, initial).getOrThrow();
            }

            @Override
            public void reset(ServerPlayer fresh, ReincarnationContext context, CompoundTag snapshot) {
                LifespanService.write(fresh, Lifespan.CODEC.parse(NbtOps.INSTANCE, snapshot).getOrThrow());
            }
        });
    }
}
