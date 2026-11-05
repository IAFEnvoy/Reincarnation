package com.iafenvoy.reincarnation.lifecycle;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

public record SoulRecord(UUID account, String name, long generation, long startedAt, long endedAt, long observedAt,
                         Component reason, String dimension, Vec3 position, String gameMode, int level,
                         int totalExperience,
                         int inventoryItems, int completedAdvancements, int deaths, long playedTicks,
                         String transactionId) {
    public static final Codec<SoulRecord> CODEC = CompoundTag.CODEC.comapFlatMap(tag -> {
        try {
            return DataResult.success(load(tag));
        } catch (RuntimeException failure) {
            return DataResult.error(() -> "Invalid life summary");
        }
    }, SoulRecord::save);

    public SoulRecord {
        reason = reason.copy();
    }

    public static SoulRecord capture(ServerPlayer player, long generation, long startedAt, long endedAt, Component reason, String transaction) {
        int items = 0;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++)
            items += player.getInventory().getItem(slot).getCount();
        return new SoulRecord(player.getUUID(), player.getGameProfile().name(), generation, startedAt, endedAt, System.currentTimeMillis(),
                reason, player.level().dimension().identifier().toString(), player.position(), player.gameMode.getGameModeForPlayer().getName(),
                player.experienceLevel, player.totalExperience, items, ((AdvancementAccess) player.getAdvancements()).reincarnation$completed(),
                player.getStats().getValue(Stats.CUSTOM.get(Stats.DEATHS)), player.getStats().getValue(Stats.CUSTOM.get(Stats.PLAY_TIME)), transaction);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("version", 1);
        tag.putString("account", this.account.toString());
        tag.putString("name", this.name);
        tag.putLong("generation", this.generation);
        tag.putLong("started", this.startedAt);
        tag.putLong("ended", this.endedAt);
        tag.putLong("observed", this.observedAt);
        tag.store("reason", ComponentSerialization.CODEC, this.reason);
        tag.putString("dimension", this.dimension);
        tag.putDouble("x", this.position.x);
        tag.putDouble("y", this.position.y);
        tag.putDouble("z", this.position.z);
        tag.putString("game_mode", this.gameMode);
        tag.putInt("level", this.level);
        tag.putInt("total_xp", this.totalExperience);
        tag.putInt("items", this.inventoryItems);
        tag.putInt("advancements", this.completedAdvancements);
        tag.putInt("deaths", this.deaths);
        tag.putLong("played_ticks", this.playedTicks);
        tag.putString("transaction", this.transactionId);
        return tag;
    }

    public static SoulRecord load(CompoundTag tag) {
        if (tag.getInt("version").orElse(-1) != 1) throw new IllegalStateException("Unsupported soul record version");
        long generation = tag.getLong("generation").orElseThrow();
        if (generation < 0) throw new IllegalStateException("Invalid soul generation");
        Component reason = tag.read("reason", ComponentSerialization.CODEC).orElseThrow();
        String legacyKey = tag.getString("reason_key").orElse("");
        if (!legacyKey.isEmpty()) reason = Component.translatableWithFallback(legacyKey, reason.getString());
        return new SoulRecord(UUID.fromString(tag.getString("account").orElseThrow()), tag.getString("name").orElseThrow(), generation,
                tag.getLong("started").orElseThrow(), tag.getLong("ended").orElseThrow(), tag.getLong("observed").orElseThrow(),
                reason, tag.getString("dimension").orElseThrow(),
                new Vec3(tag.getDouble("x").orElseThrow(), tag.getDouble("y").orElseThrow(), tag.getDouble("z").orElseThrow()),
                tag.getString("game_mode").orElseThrow(), tag.getInt("level").orElseThrow(), tag.getInt("total_xp").orElseThrow(),
                tag.getInt("items").orElseThrow(), tag.getInt("advancements").orElseThrow(), tag.getInt("deaths").orElseThrow(),
                tag.getLong("played_ticks").orElseThrow(), tag.getString("transaction").orElseThrow());
    }
}
