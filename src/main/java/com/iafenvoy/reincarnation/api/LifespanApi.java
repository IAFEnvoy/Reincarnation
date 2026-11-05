package com.iafenvoy.reincarnation.api;

import com.iafenvoy.reincarnation.lifespan.Lifespan;
import com.iafenvoy.reincarnation.lifespan.LifespanService;
import com.iafenvoy.saturn.runtime.resulting.Result;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

/**
 * Lifespan operations use ticks and run on the logical server thread, except read-only queries.
 */
public final class LifespanApi {
    private LifespanApi() {
    }

    /**
     * Reads existing data without creating an attachment; a negative remaining value means unlimited.
     */
    public static Lifespan get(Entity entity) {
        return LifespanService.value(entity);
    }

    /**
     * Rewrites both remaining life and total granted life, without immediately evaluating expiry.
     */
    public static Result<Void> set(LivingEntity entity, long ticks) {
        return LifespanService.set(entity, ticks);
    }

    /**
     * Positive amounts extend both numbers; negative amounts spend remaining life only.
     */
    public static Result<Void> add(LivingEntity entity, long ticks) {
        return LifespanService.add(entity, ticks);
    }

    /**
     * Closes the ledger until an explicit write grants finite life again.
     */
    public static Result<Void> unlimited(LivingEntity entity) {
        return LifespanService.unlimited(entity);
    }

    /**
     * Immediately performs rebirth. The returned entity replaces the old player for NEW_LIFE.
     */
    public static Result<LivingEntity> rebirth(LivingEntity entity, RebirthMode mode, Component reason) {
        return LifespanService.rebirth(entity, mode, reason);
    }

    /**
     * Queues rebirth after entity ticking. Success means accepted; events report completion or cancellation.
     */
    public static Result<Void> requestRebirth(LivingEntity entity, RebirthMode mode, Component reason) {
        return LifespanService.requestRebirth(entity, mode, reason);
    }
}
