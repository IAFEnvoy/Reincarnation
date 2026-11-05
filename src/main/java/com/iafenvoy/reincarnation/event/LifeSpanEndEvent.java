package com.iafenvoy.reincarnation.event;

import com.iafenvoy.reincarnation.config.ReincarnationServerConfig.LifespanOutcome;
import com.iafenvoy.reincarnation.lifespan.LifespanAttachment;
import net.minecraft.world.entity.Entity;
import net.neoforged.bus.api.ICancellableEvent;
import net.neoforged.neoforge.event.entity.EntityEvent;

public abstract class LifeSpanEndEvent extends EntityEvent {
    private final LifespanAttachment ledger;

    protected LifeSpanEndEvent(Entity entity, LifespanAttachment ledger) {
        super(entity);
        this.ledger = ledger;
    }

    public LifespanAttachment getSpirit() {
        return this.ledger;
    }

    /**
     * Cancel and grant positive remaining life to resume ageing; cancel alone closes the ledger.
     */
    public static final class Pre extends LifeSpanEndEvent implements ICancellableEvent {
        public Pre(Entity entity, LifespanAttachment ledger) {
            super(entity, ledger);
        }
    }

    /**
     * The entity and ledger belong to the expired life, even if a replacement player now exists.
     */
    public static final class Post extends LifeSpanEndEvent {
        private final LifespanOutcome outcome;

        public Post(Entity entity, LifespanAttachment ledger, LifespanOutcome outcome) {
            super(entity, ledger);
            this.outcome = outcome;
        }

        public LifespanOutcome getOutcome() {
            return this.outcome;
        }
    }
}
