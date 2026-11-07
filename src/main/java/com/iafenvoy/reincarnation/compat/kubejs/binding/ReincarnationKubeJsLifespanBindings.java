package com.iafenvoy.reincarnation.compat.kubejs.binding;

import com.iafenvoy.reincarnation.api.LifespanApi;
import com.iafenvoy.reincarnation.api.RebirthMode;
import com.iafenvoy.saturn.runtime.resulting.Result;
import dev.latvian.mods.kubejs.typings.Info;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

/**
 * Shared lifespan operations exposed as {@code ReincarnationLifespan}; all amounts are game ticks.
 */
public final class ReincarnationKubeJsLifespanBindings {
    @Info("Remaining ticks, or -1 for an unaccounted or closed ledger. Client reads return -1.")
    public long remaining(Entity entity) {
        return entity.level().isClientSide() ? -1L : LifespanApi.get(entity).remaining();
    }

    @Info("Total ticks granted this life; -1 if unaccounted, 0 if closed. Client reads return -1.")
    public long total(Entity entity) {
        return entity.level().isClientSide() ? -1L : LifespanApi.get(entity).total();
    }

    @Info("Rewrites remaining and total lifespan; negative ticks fail. Works while natural ageing is disabled.")
    public Result<Void> set(LivingEntity entity, long ticks) {
        return LifespanApi.set(entity, ticks);
    }

    @Info("Positive ticks extend remaining and total lifespan; negative ticks spend only the remainder.")
    public Result<Void> add(LivingEntity entity, long ticks) {
        return LifespanApi.add(entity, ticks);
    }

    @Info("Closes the ledger until an explicit write grants finite life again.")
    public Result<Void> unlimited(LivingEntity entity) {
        return LifespanApi.unlimited(entity);
    }

    @Info("Queues configured rebirth. Success means accepted; ReincarnationEvents.lifespanRebirth Post/Failed reports the outcome. Full player rebirth replaces the player object.")
    public Result<Void> reincarnate(LivingEntity entity) {
        return LifespanApi.requestRebirth(entity, RebirthMode.CONFIGURED, Component.translatable("reason.reincarnation.rebirth"));
    }
}
