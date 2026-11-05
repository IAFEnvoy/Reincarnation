package com.iafenvoy.reincarnation.lifecycle;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

/**
 * Kept in PlayerPersisted so ordinary deaths and respawns preserve the life identity.
 */
public final class LifeMarker {
    public static final String KEY = "reincarnation:life";

    private LifeMarker() {
    }

    public static CompoundTag of(ServerPlayer player) {
        return player.getPersistentData().getCompound(Player.PERSISTED_NBT_TAG).flatMap(tag -> tag.getCompound(KEY)).orElseGet(CompoundTag::new);
    }

    public static CompoundTag of(CompoundTag playerData) {
        return playerData.getCompound("NeoForgeData").flatMap(tag -> tag.getCompound(Player.PERSISTED_NBT_TAG))
                .flatMap(tag -> tag.getCompound(KEY)).orElseGet(CompoundTag::new);
    }

    public static void write(ServerPlayer player, long generation, String transaction, long started) {
        CompoundTag persistent = player.getPersistentData();
        CompoundTag kept = persistent.getCompound(Player.PERSISTED_NBT_TAG).orElseGet(CompoundTag::new);
        CompoundTag marker = new CompoundTag();
        marker.putLong("generation", generation);
        marker.putString("transaction", transaction);
        marker.putLong("started", started);
        kept.put(KEY, marker);
        persistent.put(Player.PERSISTED_NBT_TAG, kept);
    }

    public static boolean matches(CompoundTag playerData, LifeTransaction tx) {
        CompoundTag marker = of(playerData);
        return marker.getLong("generation").orElse(-1L) == tx.to()
                && marker.getString("transaction").orElse("").equals(tx.id().toString());
    }
}
