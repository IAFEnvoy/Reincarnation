package com.iafenvoy.reincarnation.event;

import com.iafenvoy.reincarnation.api.RebirthMode;
import com.iafenvoy.reincarnation.lifespan.LifespanAttachment;
import com.iafenvoy.saturn.runtime.resulting.Failure;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.bus.api.ICancellableEvent;
import net.neoforged.neoforge.event.entity.EntityEvent;

public abstract class LifeSpanRebirthEvent extends EntityEvent {
    private final LifespanAttachment ledger;
    private final RebirthMode mode;

    protected LifeSpanRebirthEvent(LivingEntity entity, LifespanAttachment ledger, RebirthMode mode) {
        super(entity);
        this.ledger = ledger;
        this.mode = mode;
    }

    public LifespanAttachment getSpirit() {
        return this.ledger;
    }

    public RebirthMode getMode() {
        return this.mode;
    }

    public static final class Pre extends LifeSpanRebirthEvent implements ICancellableEvent {
        public Pre(LivingEntity entity, LifespanAttachment ledger) {
            this(entity, ledger, RebirthMode.CONFIGURED);
        }

        public Pre(LivingEntity entity, LifespanAttachment ledger, RebirthMode mode) {
            super(entity, ledger, mode);
        }
    }

    /** Apply addon state only for RESET; full player transitions use ReincarnationHandler instead. */
    public static final class Reset extends LifeSpanRebirthEvent {
        public Reset(LivingEntity entity, LifespanAttachment ledger) {
            super(entity, ledger, RebirthMode.RESET);
        }
    }

    public static final class Failed extends LifeSpanRebirthEvent {
        private final Failure failure;

        public Failed(LivingEntity entity, LifespanAttachment ledger, RebirthMode mode, Failure failure) {
            super(entity, ledger, mode);
            this.failure = failure;
        }

        public Failure getFailure() {
            return this.failure;
        }
    }

    public static final class Post extends LifeSpanRebirthEvent {
        public Post(LivingEntity entity, LifespanAttachment ledger) {
            this(entity, ledger, RebirthMode.CONFIGURED);
        }

        public Post(LivingEntity entity, LifespanAttachment ledger, RebirthMode mode) {
            super(entity, ledger, mode);
        }
    }
}
