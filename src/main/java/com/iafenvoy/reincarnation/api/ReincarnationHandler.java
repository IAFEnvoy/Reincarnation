package com.iafenvoy.reincarnation.api;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

/**
 * Handlers must set state idempotently using the saved transaction and their own versioned snapshot.
 */
public interface ReincarnationHandler {
    Identifier id();

    default int version() {
        return 1;
    }

    default int priority() {
        return 100;
    }

    CompoundTag archive(ServerPlayer player);

    void reset(ServerPlayer fresh, ReincarnationContext context, CompoundTag snapshot);

    default void recover(ServerPlayer fresh, ReincarnationContext context, CompoundTag snapshot) {
        this.reset(fresh, context, snapshot);
    }
}
