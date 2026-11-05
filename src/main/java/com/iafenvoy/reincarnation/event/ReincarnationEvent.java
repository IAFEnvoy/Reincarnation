package com.iafenvoy.reincarnation.event;

import com.iafenvoy.reincarnation.api.ReincarnationContext;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.ICancellableEvent;

public abstract class ReincarnationEvent extends Event {
    private final ServerPlayer player;
    private final ReincarnationContext context;

    protected ReincarnationEvent(ServerPlayer player, ReincarnationContext context) {
        this.player = player;
        this.context = context;
    }

    public ServerPlayer player() {
        return this.player;
    }

    public ReincarnationContext context() {
        return this.context;
    }

    public static final class Pre extends ReincarnationEvent implements ICancellableEvent {
        public Pre(ServerPlayer player, ReincarnationContext context) {
            super(player, context);
        }
    }

    /**
     * Replayed with the same transaction during recovery. All listeners must be idempotent.
     */
    public static final class Reset extends ReincarnationEvent {
        public Reset(ServerPlayer player, ReincarnationContext context) {
            super(player, context);
        }
    }

    /**
     * Notification only. Durable state must be restored before this event.
     */
    public static final class Post extends ReincarnationEvent {
        public Post(ServerPlayer player, ReincarnationContext context) {
            super(player, context);
        }
    }
}
