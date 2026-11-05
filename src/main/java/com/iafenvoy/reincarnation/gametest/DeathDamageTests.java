package com.iafenvoy.reincarnation.gametest;

import com.iafenvoy.reincarnation.api.ReincarnationRequest;
import com.iafenvoy.reincarnation.config.ReincarnationServerConfig;
import com.iafenvoy.reincarnation.lifecycle.ReincarnationService;
import com.iafenvoy.reincarnation.lifespan.LifespanService;
import com.iafenvoy.reincarnation.registry.ReincarnationAttachments;
import com.iafenvoy.reincarnation.registry.tag.DamageTypeTags;
import com.iafenvoy.reincarnation.storage.Generations;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.tags.TagLoader;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.TagValueInput;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

final class DeathDamageTests {
    static Map<String, Consumer<GameTestHelper>> tests() {
        return Map.of("death_damage_filter", DeathDamageTests::filter,
                "death_damage_saved", DeathDamageTests::saved);
    }

    private static void ready(ServerPlayer player) {
        player.hasChangedDimension();
        player.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
        player.setGameMode(GameType.SURVIVAL);
        player.invulnerableTime = 0;
    }

    private static boolean damage(ServerPlayer player, ResourceKey<DamageType> type, float amount) {
        return player.hurtServer(player.level(), new DamageSource(
                player.registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(type)), amount);
    }

    private static ServerPlayer respawn(GameTestHelper h, ServerPlayer player, long generation) {
        h.assertTrue(player.isDeadOrDying(), "a lethal damage event precedes the respawn request");
        player.connection.handleClientCommand(new ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));
        ServerPlayer fresh = player.connection.player;
        h.assertTrue(fresh != player && fresh.isAlive(), "the respawn creates a living replacement player");
        h.assertValueEqual(Generations.current(h.getLevel().getServer(), fresh.getUUID()), generation,
                "only eligible lethal damage advances the generation");
        h.assertValueEqual(ReincarnationService.get().store().history(fresh.getUUID()).size(), (int) generation,
                "ordinary respawn creates no archive and each new life creates exactly one");
        h.assertTrue(!fresh.hasData(ReincarnationAttachments.DEATH_DAMAGE_TYPE), "respawn never copies the previous lethal damage type");
        return fresh;
    }

    private static void filter(GameTestHelper h) {
        ReincarnationServerConfig config = ReincarnationServerConfig.INSTANCE;
        String original = config.serialize();
        var registry = h.getLevel().registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE);
        Map<TagKey<DamageType>, List<Holder<DamageType>>> originalTags = new HashMap<>();
        registry.getTags().forEach(tag -> originalTags.put(tag.key(), tag.stream().toList()));
        ServerPlayer active = LifecycleTests.connect(h);
        try {
            config.lifespan.enabled.setValue(false);
            config.lifecycle.autoReincarnateOnDeath.setValue(true);
            config.lifecycle.filterDeathDamageTypes.setValue(true);
            h.assertTrue(registry.getOrThrow(LifespanService.DAMAGE_TYPE).is(DamageTypeTags.REINCARNATE_ON_DEATH),
                    "the shipped tag includes lifespan exhaustion");
            h.assertTrue(!registry.getOrThrow(DamageTypes.GENERIC_KILL).is(DamageTypeTags.REINCARNATE_ON_DEATH),
                    "ordinary kill damage is excluded by default");

            ready(active);
            h.assertTrue(damage(active, LifespanService.DAMAGE_TYPE, 1), "the earlier tagged attack deals nonlethal damage");
            h.assertTrue(!active.hasData(ReincarnationAttachments.DEATH_DAMAGE_TYPE), "nonlethal damage does not record a death");
            active.invulnerableTime = 0;
            damage(active, DamageTypes.GENERIC_KILL, Float.MAX_VALUE);
            h.assertValueEqual(active.getExistingData(ReincarnationAttachments.DEATH_DAMAGE_TYPE).orElseThrow(), DamageTypes.GENERIC_KILL,
                    "the final untagged attack determines eligibility");
            active = respawn(h, active, 0);

            ready(active);
            h.assertTrue(damage(active, DamageTypes.GENERIC_KILL, 1), "an earlier untagged attack is nonlethal");
            active.invulnerableTime = 0;
            damage(active, LifespanService.DAMAGE_TYPE, Float.MAX_VALUE);
            active = respawn(h, active, 1);
            h.assertValueEqual(ReincarnationService.get().store().history(active.getUUID(), 0).orElseThrow().reason(),
                    Component.translatable("reason.reincarnation.lifespan"), "eligible lifespan deaths retain their specific reason");

            config.lifecycle.filterDeathDamageTypes.setValue(false);
            ready(active);
            damage(active, DamageTypes.GENERIC_KILL, Float.MAX_VALUE);
            active = respawn(h, active, 2);

            config.lifecycle.filterDeathDamageTypes.setValue(true);
            config.lifecycle.autoReincarnateOnDeath.setValue(false);
            ready(active);
            damage(active, LifespanService.DAMAGE_TYPE, Float.MAX_VALUE);
            active = respawn(h, active, 2);

            config.lifecycle.autoReincarnateOnDeath.setValue(true);
            Map<TagKey<DamageType>, List<Holder<DamageType>>> changed = new HashMap<>(originalTags);
            changed.put(DamageTypeTags.REINCARNATE_ON_DEATH, List.of());
            registry.prepareTagReload(new TagLoader.LoadResult<>(Registries.DAMAGE_TYPE, changed)).apply();
            ready(active);
            damage(active, LifespanService.DAMAGE_TYPE, Float.MAX_VALUE);
            active = respawn(h, active, 2);

            ready(active);
            damage(active, DamageTypes.GENERIC_KILL, Float.MAX_VALUE);
            changed.put(DamageTypeTags.REINCARNATE_ON_DEATH, List.of(registry.getOrThrow(DamageTypes.GENERIC_KILL)));
            registry.prepareTagReload(new TagLoader.LoadResult<>(Registries.DAMAGE_TYPE, changed)).apply();
            active = respawn(h, active, 3);

            ready(active);
            damage(active, DamageTypes.GENERIC_KILL, Float.MAX_VALUE);
            active.removeData(ReincarnationAttachments.DEATH_DAMAGE_TYPE);
            active = respawn(h, active, 3);

            active = ReincarnationService.get().reincarnate(active, ReincarnationRequest.atSpawn(active, Component.literal("Manual rebirth")));
            h.assertValueEqual(Generations.current(h.getLevel().getServer(), active.getUUID()), 4L,
                    "the death damage filter does not restrict explicit rebirth requests");
            h.succeed();
        } finally {
            registry.prepareTagReload(new TagLoader.LoadResult<>(Registries.DAMAGE_TYPE, originalTags)).apply();
            config.deserialize(original);
            LifecycleTests.remove(active.connection.player);
        }
    }

    private static void saved(GameTestHelper h) {
        ServerPlayer dead = LifecycleTests.connect(h);
        try {
            ready(dead);
            damage(dead, LifespanService.DAMAGE_TYPE, Float.MAX_VALUE);
            var data = ReincarnationService.playerData(dead);
            dead.removeData(ReincarnationAttachments.DEATH_DAMAGE_TYPE);
            dead.load(TagValueInput.create(ProblemReporter.DISCARDING, dead.registryAccess(), data));
            h.assertValueEqual(dead.getExistingData(ReincarnationAttachments.DEATH_DAMAGE_TYPE).orElseThrow(), LifespanService.DAMAGE_TYPE,
                    "the lethal damage key survives the actual player NBT codec");
            for (int tick = 1; tick <= 60; tick++) h.runAfterDelay(tick, dead.connection::tick);
            h.runAfterDelay(65, () -> {
                ReincarnationServerConfig config = ReincarnationServerConfig.INSTANCE;
                String original = config.serialize();
                try {
                    h.assertTrue(dead.getLastDamageSource() == null, "the vanilla recent damage source has expired");
                    config.lifespan.enabled.setValue(false);
                    config.lifecycle.autoReincarnateOnDeath.setValue(true);
                    config.lifecycle.filterDeathDamageTypes.setValue(true);
                    respawn(h, dead, 1);
                    h.succeed();
                } finally {
                    config.deserialize(original);
                    LifecycleTests.remove(dead.connection.player);
                }
            });
        } catch (RuntimeException | Error failure) {
            LifecycleTests.remove(dead.connection.player);
            throw failure;
        }
    }
}
