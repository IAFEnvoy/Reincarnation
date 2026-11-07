package com.iafenvoy.reincarnation.gametest;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.iafenvoy.reincarnation.api.LifespanApi;
import com.iafenvoy.reincarnation.api.RebirthMode;
import com.iafenvoy.reincarnation.api.ReincarnationApi;
import com.iafenvoy.reincarnation.api.ReincarnationRequest;
import com.iafenvoy.reincarnation.config.ReincarnationServerConfig;
import com.iafenvoy.reincarnation.config.ReincarnationServerConfig.LifespanOutcome;
import com.iafenvoy.reincarnation.config.ReincarnationServerConfig.PlayerDeathMode;
import com.iafenvoy.reincarnation.data.information.getter.LifespanGetter;
import com.iafenvoy.reincarnation.event.LifeSpanEndEvent;
import com.iafenvoy.reincarnation.event.ReincarnationEvent;
import com.iafenvoy.reincarnation.lifecycle.ReincarnationService;
import com.iafenvoy.reincarnation.lifespan.Lifespan;
import com.iafenvoy.reincarnation.lifespan.LifespanAttachment;
import com.iafenvoy.reincarnation.lifespan.LifespanEvents;
import com.iafenvoy.reincarnation.lifespan.LifespanService;
import com.iafenvoy.reincarnation.registry.ReincarnationAttachments;
import com.iafenvoy.reincarnation.storage.Generations;
import com.iafenvoy.saturn.data.action.EntityAction;
import com.iafenvoy.saturn.data.condition.EntityCondition;
import com.iafenvoy.saturn.data.information.PlayerViewSnapshot;
import com.iafenvoy.saturn.runtime.resulting.Result;
import com.iafenvoy.saturn.util.formula.FormulaContext;
import com.mojang.authlib.GameProfile;
import com.mojang.serialization.JsonOps;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.TagValueInput;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

public final class LifespanTests {
    private LifespanTests() {
    }

    static Map<String, Consumer<GameTestHelper>> tests() {
        return Map.of("lifespan_ledger", LifespanTests::ledger,
                "lifespan_expiry", LifespanTests::expiry,
                "lifespan_datapack", LifespanTests::datapack,
                "lifespan_player_modes", LifespanTests::playerModes,
                "lifespan_new_life", LifespanTests::newLife,
                "lifespan_legacy", LifespanTests::legacy,
                "lifespan_recovery", LifespanTests::recovery,
                "lifespan_warning", LifespanTests::warning,
                "lifespan_death_reason", LifespanTests::deathReason,
                "lifespan_kill_on_reset", LifespanTests::killOnReset);
    }

    private record Settings(boolean enabled, long base, int age, LifespanOutcome outcome, PlayerDeathMode death,
                            RebirthMode rebirth, boolean creative, boolean spectator, boolean autoDeath,
                            double warning, boolean killOnReset) implements AutoCloseable {
        static Settings setup() {
            ReincarnationServerConfig.Lifespan c = ReincarnationServerConfig.INSTANCE.lifespan;
            Settings old = new Settings(c.enabled.getValue(), c.baseLifespan.getValue(), c.agePerSettle.getValue(), c.onExpire.getValue(),
                    c.playerDeathMode.getValue(), c.playerRebirthMode.getValue(), c.ageCreative.getValue(), c.ageSpectator.getValue(),
                    ReincarnationServerConfig.INSTANCE.lifecycle.autoReincarnateOnDeath.getValue(), c.warningFraction.getValue(), c.killOnReset.getValue());
            c.enabled.setValue(true);
            c.baseLifespan.setValue(100L);
            c.agePerSettle.setValue(20);
            c.onExpire.setValue(LifespanOutcome.NONE);
            c.playerDeathMode.setValue(PlayerDeathMode.KILL);
            c.playerRebirthMode.setValue(RebirthMode.NEW_LIFE);
            c.ageCreative.setValue(false);
            c.ageSpectator.setValue(false);
            c.warningFraction.setValue(0.1D);
            c.killOnReset.setValue(false);
            ReincarnationServerConfig.INSTANCE.lifecycle.autoReincarnateOnDeath.setValue(false);
            LifespanService.clear();
            return old;
        }

        @Override
        public void close() {
            ReincarnationServerConfig.Lifespan c = ReincarnationServerConfig.INSTANCE.lifespan;
            c.enabled.setValue(this.enabled);
            c.baseLifespan.setValue(this.base);
            c.agePerSettle.setValue(this.age);
            c.onExpire.setValue(this.outcome);
            c.playerDeathMode.setValue(this.death);
            c.playerRebirthMode.setValue(this.rebirth);
            c.ageCreative.setValue(this.creative);
            c.ageSpectator.setValue(this.spectator);
            c.warningFraction.setValue(this.warning);
            c.killOnReset.setValue(this.killOnReset);
            ReincarnationServerConfig.INSTANCE.lifecycle.autoReincarnateOnDeath.setValue(this.autoDeath);
            LifespanService.clear();
        }
    }

    private static Pig pig(GameTestHelper helper) {
        Pig pig = EntityType.PIG.create(helper.getLevel(), EntitySpawnReason.COMMAND);
        Objects.requireNonNull(pig).setNoAi(true);
        BlockPos pos = helper.absolutePos(new BlockPos(2, 2, 2));
        pig.snapTo(pos.getX(), pos.getY(), pos.getZ(), 0, 0);
        helper.getLevel().addFreshEntity(pig);
        return pig;
    }

    private static void killOnReset(GameTestHelper h) {
        try (Settings ignored = Settings.setup()) {
            Pig pig = pig(h);
            ServerPlayer old = LifecycleTests.connect(h);
            try {
                ReincarnationServerConfig.INSTANCE.lifespan.killOnReset.setValue(true);
                h.assertTrue(LifespanApi.rebirth(pig, RebirthMode.RESET, Component.literal("reset kill")).getFailure().isEmpty(), "reset succeeds with optional kill");
                h.assertTrue(pig.isDeadOrDying() && pig.getLastDamageSource().is(LifespanService.DAMAGE_TYPE), "optional reset kill uses lifespan damage");
                h.assertValueEqual(LifespanApi.get(pig), new Lifespan(100, 100), "reset finishes its ledger before killing");
                Result<LivingEntity> result = LifespanApi.rebirth(old, RebirthMode.NEW_LIFE, Component.literal("new life"));
                h.assertTrue(result.getFailure().isEmpty() && result.getData().orElseThrow().isAlive(), "kill-on-reset never kills a new-life player");
                h.succeed();
            } finally {
                pig.discard();
                LifecycleTests.remove(old.connection.player);
            }
        }
    }

    private static void ledger(GameTestHelper h) {
        try (Settings ignored = Settings.setup()) {
            Pig pig = pig(h);
            try {
                LifespanApi.add(pig, 0);
                h.assertTrue(!pig.hasData(ReincarnationAttachments.LIFESPAN), "adding zero does not open a ledger");
                h.assertTrue(!LifespanService.settle(pig), "untracked mobs never gain a ledger during tick");
                LifespanApi.add(pig, 50);
                h.assertValueEqual(LifespanApi.get(pig), new Lifespan(150, 150), "first addition includes configured base");
                h.assertValueEqual(LifespanService.display(150, 150, 1), Component.translatable("info.reincarnation.lifespan.ticks", 0L, 150L),
                        "a new ledger displays age zero rather than remaining lifespan");
                LifespanApi.add(pig, -30);
                h.assertValueEqual(LifespanApi.get(pig), new Lifespan(120, 150), "loss spends only remaining life");
                h.assertTrue(LifespanApi.set(pig, -1).getFailure().isPresent(), "negative set is rejected");
                LifespanService.settle(pig);
                Lifespan aged = LifespanApi.get(pig);
                h.assertValueEqual(LifespanService.display(aged.remaining(), aged.total(), 10),
                        Component.translatable("info.reincarnation.lifespan.value", "5.0", "15.0"), "age increases as time passes");
                LifespanApi.add(pig, 50);
                Lifespan extended = LifespanApi.get(pig);
                h.assertValueEqual(LifespanService.display(extended.remaining(), extended.total(), 10),
                        Component.translatable("info.reincarnation.lifespan.value", "5.0", "20.0"), "extending lifespan raises the limit without changing age");
                h.assertValueEqual(LifespanService.display(-1, 0, 10), Component.translatable("info.reincarnation.lifespan.unlimited"),
                        "unlimited lifespan retains its display");
                LifespanApi.set(pig, Long.MAX_VALUE);
                LifespanApi.add(pig, Long.MAX_VALUE);
                h.assertValueEqual(LifespanApi.get(pig), new Lifespan(Long.MAX_VALUE, Long.MAX_VALUE), "positive additions saturate");
                LifespanApi.add(pig, Long.MIN_VALUE);
                h.assertValueEqual(LifespanApi.get(pig).remaining(), 0L, "extreme loss clamps at zero");
                LifespanApi.unlimited(pig);
                LifespanApi.add(pig, 10);
                h.assertValueEqual(LifespanApi.get(pig), new Lifespan(10, 10), "reopening closed ledger does not count sentinel as minus one tick");
                ReincarnationServerConfig.INSTANCE.lifespan.enabled.setValue(false);
                h.assertTrue(!LifespanService.settle(pig), "disabled system does not age");
                h.assertTrue(LifespanApi.set(pig, 200).getFailure().isEmpty(), "explicit writes still work while disabled");
                h.assertTrue(Lifespan.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString("{\"lifespan_remaining\":8,\"lifespan_total\":3}")).error().isPresent(), "inconsistent ledgers are rejected");
                h.succeed();
            } finally {
                pig.discard();
            }
        }
    }

    private static void expiry(GameTestHelper h) {
        try (Settings ignored = Settings.setup()) {
            Pig pig = pig(h);
            Consumer<LifeSpanEndEvent.Pre> cancel = e -> {
                if (e.getEntity() == pig) e.setCanceled(true);
            };
            Consumer<LifeSpanEndEvent.Pre> renew = e -> {
                if (e.getEntity() == pig) {
                    e.setCanceled(true);
                    LifespanApi.add(pig, 50);
                }
            };
            List<Lifespan> ended = new ArrayList<>();
            Consumer<LifeSpanEndEvent.Post> observe = e -> {
                if (e.getEntity() == pig) ended.add(e.getSpirit().value());
            };
            NeoForge.EVENT_BUS.addListener(observe);
            try {
                LifespanApi.set(pig, 5);
                NeoForge.EVENT_BUS.addListener(cancel);
                try {
                    LifespanService.settle(pig);
                } finally {
                    NeoForge.EVENT_BUS.unregister(cancel);
                }
                h.assertValueEqual(LifespanApi.get(pig), Lifespan.CLOSED, "cancel without renewal closes the ledger");
                LifespanApi.set(pig, 5);
                NeoForge.EVENT_BUS.addListener(renew);
                try {
                    LifespanService.settle(pig);
                } finally {
                    NeoForge.EVENT_BUS.unregister(renew);
                }
                h.assertValueEqual(LifespanService.remaining(pig), 50L, "renewal inside expiry event resumes ageing");
                LifespanApi.set(pig, 5);
                LifespanService.settle(pig);
                h.assertValueEqual(LifespanApi.get(pig), Lifespan.CLOSED, "none closes the live ledger");
                ReincarnationServerConfig.INSTANCE.lifespan.onExpire.setValue(LifespanOutcome.REINCARNATE);
                LifespanApi.set(pig, 5);
                LifespanService.settle(pig);
                h.assertValueEqual(LifespanApi.get(pig), new Lifespan(100, 100), "in-place expiry starts a new ledger");
                h.assertValueEqual(ended, List.of(new Lifespan(0, 5), new Lifespan(0, 5)),
                        "end notifications retain expired numbers after closing or replacing the ledger");
                ReincarnationServerConfig.INSTANCE.lifespan.onExpire.setValue(LifespanOutcome.DEATH);
                pig.setInvulnerable(true);
                pig.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, 200, 4));
                LifespanApi.set(pig, 1);
                LifespanService.settle(pig);
                h.assertTrue(!pig.isAlive(), "lifespan damage kills a mob");
                h.assertTrue(pig.getLastDamageSource().is(LifespanService.DAMAGE_TYPE), "death uses the registered lifespan damage type");
                h.succeed();
            } finally {
                NeoForge.EVENT_BUS.unregister(observe);
                pig.discard();
            }
        }
    }

    private static EntityAction action(String json) {
        return EntityAction.SINGLE_CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json)).getOrThrow();
    }

    private static boolean condition(String json, LivingEntity entity) {
        return EntityCondition.SINGLE_CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json)).getOrThrow().test(entity, FormulaContext.of(entity));
    }

    private static void datapack(GameTestHelper h) {
        try (Settings ignored = Settings.setup()) {
            Pig pig = pig(h);
            try {
                action("{\"type\":\"reincarnation:add_lifespan\",\"amount\":0}").execute(pig, FormulaContext.of(pig));
                action("{\"type\":\"reincarnation:remove_lifespan\",\"amount\":0}").execute(pig, FormulaContext.of(pig));
                h.assertValueEqual(LifespanApi.get(pig), Lifespan.UNACCOUNTED, "zero amounts do not open a ledger");
                LifespanApi.set(pig, 0);
                action("{\"type\":\"reincarnation:add_lifespan\",\"amount\":80}").execute(pig, FormulaContext.of(pig));
                action("{\"type\":\"reincarnation:remove_lifespan\",\"amount\":\"4 * 5\"}").execute(pig, FormulaContext.of(pig));
                h.assertTrue(condition("{\"type\":\"reincarnation:lifespan\",\"comparison\":\"==\",\"compare_to\":60}", pig), "remaining condition uses modified value");
                h.assertTrue(condition("{\"type\":\"reincarnation:lifespan\",\"field\":\"fraction\",\"comparison\":\"==\",\"compare_to\":0.75}", pig), "fraction condition uses total");
                for (String type : List.of("add_lifespan", "remove_lifespan")) {
                    action("{\"type\":\"reincarnation:" + type + "\",\"amount\":-3}").execute(pig, FormulaContext.of(pig));
                    h.assertValueEqual(LifespanApi.get(pig), new Lifespan(60, 80), "negative constant does not reverse action: " + type);
                    action("{\"type\":\"reincarnation:" + type + "\",\"amount\":\"-3\"}").execute(pig, FormulaContext.of(pig));
                    h.assertValueEqual(LifespanApi.get(pig), new Lifespan(60, 80), "negative formula does not reverse action: " + type);
                }
                action("{\"type\":\"reincarnation:remove_lifespan\",\"amount\":10000}").execute(pig, FormulaContext.of(pig));
                h.assertValueEqual(LifespanApi.get(pig), new Lifespan(0, 80), "removal clamps to zero without changing total");
                action("{\"type\":\"reincarnation:unlimited_lifespan\"}").execute(pig, FormulaContext.of(pig));
                h.assertTrue(condition("{\"type\":\"reincarnation:lifespan_state\",\"state\":\"unlimited\"}", pig), "unlimited state condition");
                h.assertTrue(!condition("{\"type\":\"reincarnation:lifespan\",\"comparison\":\"<\",\"compare_to\":0}", pig), "unlimited sentinel is not a negative finite lifespan");
                action("{\"type\":\"reincarnation:reincarnate\",\"mode\":\"reset\"}").execute(pig, FormulaContext.of(pig));
                h.assertTrue(!LifespanApi.get(pig).finite(), "action defers rebirth until queue is flushed");
                LifespanService.flush();
                h.assertValueEqual(LifespanApi.get(pig), new Lifespan(100, 100), "queued action reopens ledger");
                h.succeed();
            } finally {
                pig.discard();
            }
        }
    }

    private static void playerModes(GameTestHelper h) {
        try (Settings ignored = Settings.setup()) {
            ServerPlayer player = LifecycleTests.connect(h);
            try {
                player.setGameMode(GameType.CREATIVE);
                LifespanApi.set(player, 10);
                h.assertTrue(!LifespanService.settle(player), "creative players do not age by default");
                player.setGameMode(GameType.SURVIVAL);
                ReincarnationServerConfig.INSTANCE.lifespan.onExpire.setValue(LifespanOutcome.DEATH);
                ReincarnationServerConfig.INSTANCE.lifespan.playerDeathMode.setValue(PlayerDeathMode.SPECTATOR);
                LifespanService.settle(player);
                h.assertTrue(player.isAlive() && player.isSpectator(), "spectator expiry does not kill");
                h.assertValueEqual(LifespanService.remaining(player), 0L, "spectator keeps an exhausted ledger");
                h.assertValueEqual(Generations.current(h.getLevel().getServer(), player.getUUID()), 0L, "spectator expiry does not advance generation");
                player.setGameMode(GameType.SURVIVAL);
                ReincarnationServerConfig.INSTANCE.lifespan.playerDeathMode.setValue(PlayerDeathMode.KILL);
                player.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
                LifespanApi.set(player, 10);
                LifespanService.settle(player);
                h.assertTrue(player.isDeadOrDying(), "kill mode performs real player death");
                player = h.getLevel().getServer().getPlayerList().respawn(player, false, Entity.RemovalReason.KILLED);
                h.assertValueEqual(LifespanService.remaining(player), 0L, "ordinary respawn copies lifespan");
                h.succeed();
            } finally {
                LifecycleTests.remove(player.connection.player);
            }
        }
    }

    private static void warning(GameTestHelper h) {
        try (Settings ignored = Settings.setup()) {
            ServerPlayer player = LifecycleTests.connect(h);
            try {
                ReincarnationServerConfig.Lifespan settings = ReincarnationServerConfig.INSTANCE.lifespan;
                player.setGameMode(GameType.SURVIVAL);
                LifespanApi.set(player, 100);
                settings.agePerSettle.setValue(10);
                for (int i = 0; i < 8; i++) LifespanService.settle(player);
                h.assertTrue(warnings(player).isEmpty(), "remaining life above ten percent does not warn");
                LifespanService.settle(player);
                List<ClientboundSystemChatPacket> first = warnings(player);
                h.assertValueEqual(first.size(), 1, "reaching exactly ten percent sends one warning");
                h.assertTrue(!first.getFirst().overlay(), "warnings remain in chat instead of disappearing from the action bar");
                h.assertValueEqual(((TranslatableContents) first.getFirst().content().getContents()).getArgs()[0],
                        LifespanService.display(10, 100, settings.ticksPerYear.getValue()), "warning displays the current age and lifespan limit");
                settings.agePerSettle.setValue(1);
                LifespanService.settle(player);
                h.assertTrue(warnings(player).isEmpty(), "remaining below the threshold does not spam warnings");
                LifespanApi.add(player, 100);
                LifespanApi.add(player, -89);
                h.assertValueEqual(warnings(player).size(), 1, "recovering above the threshold permits a later warning");

                settings.warningFraction.setValue(0.0D);
                LifespanApi.set(player, 100);
                LifespanApi.add(player, -95);
                h.assertTrue(warnings(player).isEmpty(), "zero disables warnings even below the threshold");
                settings.warningFraction.setValue(0.1D);
                settings.agePerSettle.setValue(0);
                LifespanService.settle(player);
                h.assertValueEqual(warnings(player).size(), 1, "enabling a warning while already below the threshold sends a pending warning");
                LifespanService.settle(player);
                h.assertTrue(warnings(player).isEmpty(), "zero ageing does not repeat the pending warning");

                LifespanApi.set(player, 101);
                LifespanApi.add(player, -90);
                h.assertTrue(warnings(player).isEmpty(), "rounding does not warn above the configured fraction");
                LifespanApi.add(player, -1);
                h.assertValueEqual(warnings(player).size(), 1, "fractional thresholds warn only once the ratio is low enough");
                JsonElement saved = LifespanAttachment.CODEC.codec().encodeStart(JsonOps.INSTANCE, player.getData(ReincarnationAttachments.LIFESPAN)).getOrThrow();
                player.setData(ReincarnationAttachments.LIFESPAN, LifespanAttachment.CODEC.codec().parse(JsonOps.INSTANCE, saved).getOrThrow());
                LifespanEvents.login(new PlayerEvent.PlayerLoggedInEvent(player));
                h.assertValueEqual(warnings(player).size(), 1, "loading an already-low ledger warns on login");
                LifespanEvents.login(new PlayerEvent.PlayerLoggedInEvent(player));
                h.assertTrue(warnings(player).isEmpty(), "repeated login processing does not repeat a warning");
                settings.warningFraction.setValue(1.0D);
                LifespanApi.unlimited(player);
                LifespanApi.set(player, 100);
                h.assertValueEqual(warnings(player).size(), 1, "a threshold of one is honored");
                LifespanApi.unlimited(player);
                LifespanApi.set(player, 0);
                h.assertTrue(warnings(player).isEmpty(), "unlimited and zero-total ledgers never warn");
                h.succeed();
            } finally {
                LifecycleTests.remove(player.connection.player);
            }
        }
    }

    private static List<ClientboundSystemChatPacket> warnings(ServerPlayer player) {
        List<ClientboundSystemChatPacket> warnings = new ArrayList<>();
        EmbeddedChannel channel = (EmbeddedChannel) player.connection.getConnection().channel();
        Object packet;
        while ((packet = channel.readOutbound()) != null)
            if (packet instanceof ClientboundSystemChatPacket chat && chat.content().getContents() instanceof TranslatableContents text
                    && text.getKey().equals("message.reincarnation.lifespan.warning")) warnings.add(chat);
        return warnings;
    }

    private static void deathReason(GameTestHelper h) {
        Settings settings = Settings.setup();
        ServerPlayer dead = LifecycleTests.connect(h);
        try {
            dead.setGameMode(GameType.SURVIVAL);
            dead.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
            ReincarnationServerConfig.INSTANCE.lifespan.onExpire.setValue(LifespanOutcome.DEATH);
            LifespanApi.set(dead, 1);
            LifespanService.settle(dead);
            h.assertTrue(dead.isDeadOrDying(), "lifespan exhaustion triggers real death");
            h.assertValueEqual(dead.getExistingData(ReincarnationAttachments.DEATH_REASON).orElseThrow(),
                    Component.translatable("reason.reincarnation.lifespan"), "the lethal lifespan damage records a translatable reason");
            CompoundTag saved = ReincarnationService.playerData(dead);
            dead.removeData(ReincarnationAttachments.DEATH_REASON);
            dead.load(TagValueInput.create(ProblemReporter.DISCARDING, dead.registryAccess(), saved));
            h.assertValueEqual(dead.getExistingData(ReincarnationAttachments.DEATH_REASON).orElseThrow(),
                    Component.translatable("reason.reincarnation.lifespan"), "the reason survives the actual player NBT codec");
            for (int tick = 1; tick <= 60; tick++) h.runAfterDelay(tick, dead.connection::tick);
            h.runAfterDelay(65, () -> {
                try {
                    h.assertTrue(dead.getLastDamageSource() == null, "vanilla has forgotten its recent damage source while the death screen remains open");
                    ReincarnationServerConfig.INSTANCE.lifecycle.autoReincarnateOnDeath.setValue(true);
                    dead.connection.handleClientCommand(new ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));
                    ServerPlayer fresh = dead.connection.player;
                    h.assertTrue(fresh != dead && fresh.isAlive(), "a delayed lifespan death can reincarnate normally");
                    h.assertValueEqual(ReincarnationService.get().store().history(fresh.getUUID(), 0).orElseThrow().reason(),
                            Component.translatable("reason.reincarnation.lifespan"), "the archive records lifespan exhaustion after the damage-source timeout");
                    h.assertTrue(!fresh.hasData(ReincarnationAttachments.DEATH_REASON), "a new life does not inherit the previous death reason");
                    fresh.hasChangedDimension();
                    fresh.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
                    fresh.hurtServer(fresh.level(), fresh.damageSources().genericKill(), Float.MAX_VALUE);
                    fresh.connection.handleClientCommand(new ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));
                    h.assertValueEqual(ReincarnationService.get().store().history(fresh.getUUID(), 1).orElseThrow().reason(),
                            Component.translatable("reason.reincarnation.death"), "a subsequent ordinary death keeps its own reason");
                    h.succeed();
                } finally {
                    LifecycleTests.remove(dead.connection.player);
                    settings.close();
                }
            });
        } catch (RuntimeException | Error failure) {
            LifecycleTests.remove(dead.connection.player);
            settings.close();
            throw failure;
        }
    }

    private static void newLife(GameTestHelper h) {
        try (Settings ignored = Settings.setup()) {
            ServerPlayer old = LifecycleTests.connect(h);
            try {
                old.setGameMode(GameType.SURVIVAL);
                LifespanApi.set(old, 5);
                ReincarnationServerConfig.INSTANCE.lifespan.onExpire.setValue(LifespanOutcome.REINCARNATE);
                LifespanService.settle(old);
                h.assertTrue(old.connection.player == old, "expiry never replaces player during entity tick");
                h.assertTrue(LifespanApi.requestRebirth(old, RebirthMode.NEW_LIFE, Component.literal("duplicate")).getFailure().isPresent(), "pending requests deduplicate by account");
                LifespanService.flush();
                ServerPlayer fresh = old.connection.player;
                h.assertTrue(fresh != old, "complete rebirth replaces the player");
                h.assertValueEqual(Generations.current(h.getLevel().getServer(), old.getUUID()), 1L, "one expiry advances one generation");
                h.assertValueEqual(LifespanApi.get(fresh), new Lifespan(100, 100), "new life starts with configured baseline");
                PlayerViewSnapshot past = ReincarnationApi.view(old.getUUID(), 0).orElseThrow();
                h.assertValueEqual(ReincarnationService.get().store().history(old.getUUID(), 0).orElseThrow().reason(),
                        Component.translatable("reason.reincarnation.lifespan"), "direct lifespan reincarnation uses the same exhaustion reason");
                h.assertValueEqual(past.read(LifespanGetter.INSTANCE).value().orElseThrow(), new Lifespan(0, 5), "old life retains exhausted ledger");
                Lifespan oldLifespan = past.read(LifespanGetter.INSTANCE).value().orElseThrow();
                h.assertValueEqual(LifespanService.display(oldLifespan.remaining(), oldLifespan.total(), 1),
                        Component.translatable("info.reincarnation.lifespan.ticks", 5L, 5L), "an expired past life displays its reached age");
                Lifespan freshLifespan = LifespanApi.get(fresh);
                h.assertValueEqual(LifespanService.display(freshLifespan.remaining(), freshLifespan.total(), 1),
                        Component.translatable("info.reincarnation.lifespan.ticks", 0L, 100L), "rebirth starts the displayed age at zero");
                h.assertTrue(condition("{\"type\":\"reincarnation:generation\",\"comparison\":\"==\",\"compare_to\":2}", fresh), "generation condition uses visible one-based life number");
                LifespanService.flush();
                h.assertValueEqual(Generations.current(h.getLevel().getServer(), fresh.getUUID()), 1L, "second flush does not repeat transition");
                ReincarnationServerConfig.INSTANCE.lifespan.playerRebirthMode.setValue(RebirthMode.RESET);
                LifespanApi.set(fresh, 1);
                LifespanService.settle(fresh);
                h.assertTrue(fresh.connection.player == fresh, "configured reset preserves player object");
                h.assertValueEqual(LifespanApi.get(fresh), new Lifespan(100, 100), "in-place reset reopens ledger");
                Consumer<ReincarnationEvent.Pre> veto = event -> {
                    if (event.player().getUUID().equals(old.getUUID())) event.setCanceled(true);
                };
                NeoForge.EVENT_BUS.addListener(veto);
                try {
                    h.assertTrue(LifespanApi.rebirth(fresh, RebirthMode.NEW_LIFE, Component.literal("cancel")).getFailure().isPresent(),
                            "full transition cancellation returns an API failure");
                } finally {
                    NeoForge.EVENT_BUS.unregister(veto);
                }
                h.assertValueEqual(Generations.current(h.getLevel().getServer(), fresh.getUUID()), 1L, "cancelled transition does not advance generation");
                LifespanApi.requestRebirth(fresh, RebirthMode.NEW_LIFE, Component.literal("stale"));
                ServerPlayer changed = ReincarnationApi.reincarnate(fresh, ReincarnationRequest.atSpawn(fresh, Component.literal("manual")));
                LifespanService.flush();
                h.assertTrue(changed.connection.player == changed, "stale queued request cannot replace an already reborn player");
                h.assertValueEqual(Generations.current(h.getLevel().getServer(), changed.getUUID()), 2L, "stale request does not advance generation twice");
                h.succeed();
            } finally {
                LifecycleTests.remove(old.connection.player);
            }
        }
    }

    private static void legacy(GameTestHelper h) {
        try (Settings ignored = Settings.setup()) {
            Pig pig = pig(h);
            try {
                pig.setData(ReincarnationAttachments.LEGACY_LIFESPAN, new LifespanAttachment(new Lifespan(31, 80)));
                h.assertValueEqual(LifespanApi.get(pig), new Lifespan(31, 80), "legacy values are readable without mutation");
                h.assertTrue(!pig.hasData(ReincarnationAttachments.LIFESPAN), "queries do not migrate or create attachments");
                LifespanService.migrate(pig);
                h.assertValueEqual(LifespanApi.get(pig), new Lifespan(31, 80), "migration preserves both values");
                h.assertTrue(!pig.hasData(ReincarnationAttachments.LEGACY_LIFESPAN), "legacy attachment removed after migration");
                JsonElement tag = LifespanAttachment.CODEC.codec().encodeStart(JsonOps.INSTANCE, pig.getData(ReincarnationAttachments.LIFESPAN)).getOrThrow();
                h.assertValueEqual(LifespanAttachment.CODEC.codec().parse(JsonOps.INSTANCE, tag).getOrThrow().value(), new Lifespan(31, 80), "attachment codec roundtrip");
                h.succeed();
            } finally {
                pig.discard();
            }
        }
    }

    private static void recovery(GameTestHelper h) {
        try (Settings ignored = Settings.setup()) {
            ServerPlayer old = LifecycleTests.connect(h);
            GameProfile profile = old.getGameProfile();
            Consumer<ReincarnationEvent.Reset> fail = event -> {
                if (event.player().getUUID().equals(profile.id()))
                    throw new IllegalStateException("Intentional lifespan recovery test");
            };
            NeoForge.EVENT_BUS.addListener(fail);
            try {
                try {
                    ReincarnationApi.reincarnate(old, ReincarnationRequest.atSpawn(old, Component.literal("recovery")));
                    h.fail("expected reset failure");
                } catch (IllegalStateException expected) {
                    h.assertTrue(ReincarnationService.get().pending(old.getUUID()).isPresent(), "transaction remains recoverable");
                }
            } finally {
                NeoForge.EVENT_BUS.unregister(fail);
            }
            ServerPlayer partial = h.getLevel().getServer().getPlayerList().getPlayer(old.getUUID());
            if (partial != null) LifecycleTests.remove(partial);
            ReincarnationServerConfig.INSTANCE.lifespan.baseLifespan.setValue(900L);
            ReincarnationService.stop();
            ReincarnationService.start(h.getLevel().getServer());
            ServerPlayer fresh = LifecycleTests.connect(h, profile);
            try {
                h.assertTrue(ReincarnationService.get().pending(fresh.getUUID()).isEmpty(), "login completes interrupted transition");
                h.assertValueEqual(LifespanApi.get(fresh), new Lifespan(100, 100), "recovery uses archived baseline rather than changed config");
                h.succeed();
            } finally {
                LifecycleTests.remove(fresh);
            }
        }
    }
}
