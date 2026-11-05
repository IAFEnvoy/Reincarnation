package com.iafenvoy.reincarnation.gametest;

import com.iafenvoy.jupiter.ConfigManager;
import com.iafenvoy.jupiter.ServerConfigManager;
import com.iafenvoy.reincarnation.api.*;
import com.iafenvoy.reincarnation.config.ReincarnationServerConfig;
import com.iafenvoy.reincarnation.event.RegisterReincarnationHandlersEvent;
import com.iafenvoy.reincarnation.event.ReincarnationEvent;
import com.iafenvoy.reincarnation.lifecycle.*;
import com.iafenvoy.reincarnation.mixin.ServerPlayerResetAccess;
import com.iafenvoy.reincarnation.reference.ReferencePersistence;
import com.iafenvoy.reincarnation.storage.Generations;
import com.iafenvoy.saturn.util.FileAccess;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.serialization.Codec;
import io.netty.channel.embedded.EmbeddedChannel;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.*;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.server.players.NameAndId;
import net.minecraft.stats.ServerStatsCounter;
import net.minecraft.stats.Stat;
import net.minecraft.stats.Stats;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.TicketStorage;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.ScoreHolder;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import org.jspecify.annotations.NonNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

final class LifecycleTests {
    private static final DeferredRegister<AttachmentType<?>> ATTACHMENTS = DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, ReferenceTests.MOD_ID);
    private static final Supplier<AttachmentType<Integer>> EXTRA = ATTACHMENTS.register("gametest/extra", () -> AttachmentType.builder(() -> 0).serialize(Codec.INT.fieldOf("value")).build());
    private static final Set<UUID> FAIL = new HashSet<>();
    private static final Set<UUID> CANCEL = new HashSet<>();
    private static final Set<UUID> REENTER = new HashSet<>();
    private static final Map<UUID, UUID> RECOVERED = new HashMap<>();
    private static final Map<UUID, BiConsumer<String, ServerPlayer>> OBSERVERS = new HashMap<>();

    static Map<String, Consumer<GameTestHelper>> tests() {
        return Map.ofEntries(Map.entry("lifecycle_rebirth", LifecycleTests::rebirth),
                Map.entry("lifecycle_fresh_initialization", LifecycleTests::freshInitialization),
                Map.entry("lifecycle_cancel", LifecycleTests::cancel),
                Map.entry("lifecycle_recovery", LifecycleTests::recovery),
                Map.entry("lifecycle_finalize", LifecycleTests::finalizeWithoutReset),
                Map.entry("lifecycle_io_failure", LifecycleTests::ioFailure),
                Map.entry("lifecycle_policy", LifecycleTests::policy),
                Map.entry("lifecycle_intent_replay", LifecycleTests::intentReplay),
                Map.entry("server_configuration", LifecycleTests::configuration),
                Map.entry("lifecycle_death", LifecycleTests::death),
                Map.entry("lifecycle_delayed_death", LifecycleTests::delayedDeath));
    }

    static void registerHandlers(IEventBus bus) {
        ATTACHMENTS.register(bus);
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, (PlayerEvent.Clone event) -> observe("clone", event.getEntity()));
        NeoForge.EVENT_BUS.addListener(EventPriority.LOW, (PlayerEvent.PlayerRespawnEvent event) -> observe("respawn", event.getEntity()));
        NeoForge.EVENT_BUS.addListener(EventPriority.LOW, (PlayerEvent.PlayerLoggedInEvent event) -> observe("login", event.getEntity()));
        bus.addListener((RegisterReincarnationHandlersEvent event) -> event.register(new ReincarnationHandler() {
            @Override
            public Identifier id() {
                return Identifier.fromNamespaceAndPath(ReferenceTests.MOD_ID, "gametest/life_handler");
            }

            @Override
            public CompoundTag archive(ServerPlayer player) {
                CompoundTag tag = new CompoundTag();
                tag.putInt("kept", player.getPersistentData().getInt("test:kept").orElse(0));
                return tag;
            }

            @Override
            public void reset(ServerPlayer player, ReincarnationContext context, CompoundTag data) {
                if (FAIL.remove(player.getUUID())) throw new IllegalStateException("Intentional test handler failure");
                player.getPersistentData().putInt("test:kept", data.getInt("kept").orElseThrow());
                if (context.recovering()) RECOVERED.put(player.getUUID(), context.transactionId());
            }
        }));
        NeoForge.EVENT_BUS.addListener((ReincarnationEvent.Pre event) -> {
            UUID account = event.player().getUUID();
            if (REENTER.remove(account)) {
                try {
                    ReincarnationService.get().reincarnate(event.player(), event.context().request());
                    throw new AssertionError("Reentrant transaction accepted");
                } catch (IllegalStateException _) {
                }
            }
            if (CANCEL.remove(account)) event.setCanceled(true);
        });
    }

    private static void observe(String phase, Player player) {
        BiConsumer<String, ServerPlayer> observer = OBSERVERS.get(player.getUUID());
        if (observer != null && player instanceof ServerPlayer serverPlayer) observer.accept(phase, serverPlayer);
    }

    private static ReincarnationRequest request(ServerPlayer player) {
        return new ReincarnationRequest(player.level().dimension(), player.position(), 0, 0, Component.literal("测试转生"), ResetPolicy.DEFAULT);
    }

    static ServerPlayer connect(GameTestHelper helper, GameProfile profile) {
        MinecraftServer server = helper.getLevel().getServer();
        Optional<CompoundTag> data = server.getPlayerList().loadPlayerData(new NameAndId(profile));
        CommonListenerCookie cookie = CommonListenerCookie.createInitial(profile, false);
        ServerPlayer player = new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(), profile, cookie.clientInformation());
        BlockPos pos = helper.absolutePos(new BlockPos(1, 2, 1));
        player.snapTo(pos.getX(), pos.getY(), pos.getZ(), 0, 0);
        data.ifPresent(tag -> player.load(TagValueInput.create(ProblemReporter.DISCARDING, server.registryAccess(), tag)));
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        new EmbeddedChannel(connection);
        helper.getLevel().getServer().getPlayerList().placeNewPlayer(connection, player, cookie);
        return player;
    }

    static ServerPlayer connect(GameTestHelper helper) {
        UUID id = UUID.randomUUID();
        return connect(helper, new GameProfile(id, "Life" + id.toString().substring(0, 8)));
    }

    static void remove(ServerPlayer player) {
        if (player.level().getServer().getPlayerList().getPlayer(player.getUUID()) == player)
            player.level().getServer().getPlayerList().remove(player);
        player.connection.getConnection().disconnect(Component.literal("Test complete"));
    }

    private static void rebirth(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ReincarnationService service = ReincarnationService.get();
        ServerPlayer old = connect(helper);
        ServerPlayer active = old;
        ServerPlayer neighbor = connect(helper);
        boolean keep = old.level().getGameRules().get(GameRules.KEEP_INVENTORY);
        try {
            old.level().getGameRules().set(GameRules.KEEP_INVENTORY, true, server);
            old.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 13));
            old.getEnderChestInventory().setItem(0, new ItemStack(Items.EMERALD, 7));
            old.giveExperiencePoints(250);
            old.getFoodData().setFoodLevel(5);
            old.getPersistentData().putInt("test:kept", 42);
            old.getPersistentData().putString("thirdparty:unknown", "retain");
            old.setData(EXTRA.get(), 37);
            old.getInventory().setSelectedSlot(4);
            old.getAttribute(Attributes.MAX_HEALTH).setBaseValue(60);
            old.getStats().setValue(old, Stats.CUSTOM.get(Stats.DEATHS), 7);
            ResourceKey<Recipe<?>> recipe = ResourceKey.create(Registries.RECIPE, Identifier.withDefaultNamespace("crafting_table"));
            old.getRecipeBook().add(recipe);
            AdvancementHolder advancement = server.getAdvancements().get(Identifier.withDefaultNamespace("story/root"));
            helper.assertTrue(advancement != null, "vanilla advancement available");
            for (String criterion : advancement.value().criteria().keySet())
                old.getAdvancements().award(advancement, criterion);
            CompoundTag shoulder = new CompoundTag();
            shoulder.putString("id", "minecraft:parrot");
            ((ServerPlayerResetAccess) old).reincarnation$shoulderLeft(shoulder);
            Objective objective = server.getScoreboard().addObjective("test_" + old.getId(), ObjectiveCriteria.DUMMY, Component.literal("Test"), ObjectiveCriteria.RenderType.INTEGER, false, null);
            server.getScoreboard().getOrCreatePlayerScore(old, objective).set(19);
            ScoreHolder other = ScoreHolder.forNameOnly("unrelated_" + old.getId());
            server.getScoreboard().getOrCreatePlayerScore(other, objective).set(29);
            PlayerTeam team = server.getScoreboard().addPlayerTeam("t_" + old.getId());
            server.getScoreboard().addPlayerToTeam(old.getScoreboardName(), team);
            Wolf wolf = EntityType.WOLF.create(helper.getLevel(), EntitySpawnReason.COMMAND);
            wolf.snapTo(old.position());
            wolf.setOwner(old);
            wolf.setTame(true, true);
            wolf.setOrderedToSit(true);
            helper.getLevel().addFreshEntity(wolf);
            EntityReference<LivingEntity> oldReference = wolf.getOwnerReference();
            ThrownEnderpearl pearl = new ThrownEnderpearl(old.level(), old, new ItemStack(Items.ENDER_PEARL));
            old.level().addFreshEntity(pearl);
            old.registerAndUpdateEnderPearlTicket(pearl);
            ThrownEnderpearl otherPearl = new ThrownEnderpearl(neighbor.level(), neighbor, new ItemStack(Items.ENDER_PEARL));
            otherPearl.snapTo(pearl.position());
            neighbor.level().addFreshEntity(otherPearl);
            neighbor.registerAndUpdateEnderPearlTicket(otherPearl);
            ServerStatsCounter stats = old.getStats();
            PlayerAdvancements advancements = old.getAdvancements();
            ServerGamePacketListenerImpl connection = old.connection;
            EmbeddedChannel channel = (EmbeddedChannel) connection.getConnection().channel();
            while (channel.readOutbound() != null) {
            }
            List<String> phases = new ArrayList<>();
            OBSERVERS.put(old.getUUID(), (phase, fresh) -> {
                phases.add(phase);
                helper.assertTrue(fresh.getInventory().isEmpty() && fresh.getEnderChestInventory().isEmpty(), "inventories are fresh during " + phase);
                helper.assertTrue(fresh.getFoodData() != old.getFoodData() && fresh.getEnderChestInventory() != old.getEnderChestInventory(), "mutable containers are not shared during " + phase);
                helper.assertTrue(fresh.totalExperience == 0 && fresh.getMaxHealth() == 20 && fresh.getFoodData().getFoodLevel() == 20,
                        "gameplay starts at constructor defaults during " + phase);
                helper.assertTrue(fresh.getShoulderEntityLeft().isEmpty() && !fresh.getRecipeBook().contains(recipe), "old shoulders and recipes are never copied during " + phase);
            });
            old.setTabListHeaderFooter(Component.literal("Header"), Component.literal("Footer"));
            active = service.reincarnate(old, request(old));
            OBSERVERS.remove(old.getUUID());
            helper.assertValueEqual(phases, List.of("clone", "respawn"), "normal lifecycle events each run once");
            helper.assertTrue(active.getTabListHeader().equals(old.getTabListHeader()) && active.getTabListFooter().equals(old.getTabListFooter()), "account tab list presentation is retained");
            helper.assertTrue(active != old && active.getUUID().equals(old.getUUID()), "new entity, stable account");
            helper.assertTrue(connection.player == active && active.connection == connection, "connection uses replacement player");
            helper.assertValueEqual(Generations.current(server, old.getUUID()), 1L, "generation committed");
            helper.assertTrue(active.getStats() != stats && active.getAdvancements() != advancements, "fresh progress caches");
            helper.assertTrue(active.getInventory().isEmpty() && active.getEnderChestInventory().isEmpty(), "inventory and ender chest reset despite keepInventory");
            helper.assertValueEqual(active.totalExperience, 0, "experience reset");
            helper.assertValueEqual(active.getFoodData().getFoodLevel(), 20, "food reset");
            helper.assertValueEqual(active.getMaxHealth(), 20.0F, "copied attribute baseline reset");
            helper.assertTrue(active.getShoulderEntityLeft().isEmpty() && !active.getRecipeBook().contains(recipe), "shoulder and recipes reset");
            helper.assertValueEqual(active.getStats().getValue(Stats.CUSTOM.get(Stats.DEATHS)), 0, "stats reset");
            helper.assertTrue(!active.getAdvancements().getOrStartProgress(advancement).hasProgress(), "advancement reset");
            helper.assertTrue(server.getScoreboard().listPlayerScores(active).isEmpty(), "personal scores reset");
            helper.assertValueEqual(server.getScoreboard().getPlayerScoreInfo(other, objective).value(), 29, "other scores retained");
            helper.assertTrue(active.getTeam() == team, "team retained by default");
            helper.assertValueEqual(active.getPersistentData().getString("thirdparty:unknown").orElseThrow(), "retain", "unknown mod NBT retained");
            helper.assertValueEqual(active.getPersistentData().getInt("test:kept").orElseThrow(), 42, "handler snapshot applied");
            helper.assertTrue(!active.hasData(EXTRA.get()), "unlisted attachment is cleared by default");
            helper.assertValueEqual(active.getInventory().getSelectedSlot(), 0, "hotbar selection reset");
            helper.assertTrue(!old.getPersistentData().contains(ReferencePersistence.METADATA), "output metadata did not mutate live persistent data");
            boolean statsZero = false, advancementReset = false;
            Object packet;
            while ((packet = channel.readOutbound()) != null) {
                if (packet instanceof ClientboundAwardStatsPacket(
                        Object2IntMap<Stat<?>> stats1
                ))
                    statsZero |= stats1.containsKey(Stats.CUSTOM.get(Stats.DEATHS)) && stats1.getInt(Stats.CUSTOM.get(Stats.DEATHS)) == 0;
                if (packet instanceof ClientboundUpdateAdvancementsPacket values)
                    advancementReset |= values.shouldReset();
            }
            helper.assertTrue(statsZero && advancementReset, "client receives old-stat zero values and advancement replacement");
            helper.assertTrue(oldReference.getEntity(helper.getLevel(), LivingEntity.class) == null && !wolf.isTame() && wolf.getOwnerReference() == null, "old pet relationship invalidated and cleaned");
            helper.assertTrue(pearl.isRemoved() && old.getEnderPearls().isEmpty(), "flying pearls removed");
            helper.assertTrue(!otherPearl.isRemoved() && helper.getLevel().getDataStorage().computeIfAbsent(TicketStorage.TYPE)
                            .getTickets(otherPearl.chunkPosition().pack()).stream().anyMatch(ticket -> ticket.getType() == TicketType.ENDER_PEARL),
                    "another player's pearl keeps the shared chunk ticket");
            neighbor.deregisterEnderPearl(otherPearl);
            otherPearl.setOwner(null);
            otherPearl.discard();
            SoulRecord record = service.store().history(old.getUUID(), 0).orElseThrow();
            helper.assertValueEqual(record.deaths(), 7, "archive includes unsaved stats");
            helper.assertTrue(record.inventoryItems() >= 13 && record.completedAdvancements() >= 1, "archive includes unsaved inventory and advancement");
            helper.assertTrue(!service.canSave(old) && service.canSave(active), "old objects cannot overwrite new player data");
            wolf.setOwner(active);
            wolf.setTame(true, true);
            TagValueOutput petData = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, helper.getLevel().registryAccess());
            wolf.saveWithoutId(petData);
            helper.assertValueEqual(LifeReferences.generation(wolf.getOwnerReference()).orElse(-1L), 1L, "new relationship binds current generation");
            // Ordinary respawn preserves this life; the next rebirth advances exactly once again.
            ServerPlayer ordinary = server.getPlayerList().respawn(active, false, Entity.RemovalReason.CHANGED_DIMENSION);
            ordinary.connection.player = ordinary;
            active = ordinary;
            helper.assertValueEqual(LifeMarker.of(active).getLong("generation").orElseThrow(), 1L, "normal respawn preserves marker");
            active = service.reincarnate(active, request(active));
            helper.assertValueEqual(Generations.current(server, active.getUUID()), 2L, "second rebirth advances once");
            helper.assertValueEqual(service.store().history(active.getUUID()).size(), 2, "two immutable life records");
            helper.assertTrue(!wolf.isTame(), "generation-one pet expires on generation two");
            service.validateLoad(active.getUUID(), server.getPlayerList().loadPlayerData(active.nameAndId()));
            server.getScoreboard().removeObjective(objective);
            server.getScoreboard().removePlayerTeam(team);
            helper.succeed();
        } finally {
            OBSERVERS.remove(old.getUUID());
            old.level().getGameRules().set(GameRules.KEEP_INVENTORY, keep, server);
            remove(active);
            remove(neighbor);
        }
    }

    private static void freshInitialization(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ReincarnationService service = ReincarnationService.get();
        ServerPlayer active = connect(helper);
        UUID account = active.getUUID();
        List<String> phases = new ArrayList<>();
        try {
            active.addTag("old-life");
            active.getPersistentData().putString("test:old", "discard");
            active.setData(EXTRA.get(), 37);
            OBSERVERS.put(account, (phase, fresh) -> {
                phases.add(phase);
                if (!phase.equals("respawn")) {
                    helper.assertTrue(fresh.getInventory().isEmpty() && fresh.entityTags().isEmpty() && !fresh.hasData(EXTRA.get()),
                            "listeners receive fresh state during " + phase);
                    fresh.getInventory().setItem(0, new ItemStack(Items.APPLE, 3));
                    fresh.getEnderChestInventory().setItem(0, new ItemStack(Items.EMERALD, 2));
                    fresh.giveExperiencePoints(17);
                    fresh.getAttribute(Attributes.MAX_HEALTH).setBaseValue(40);
                    fresh.setHealth(35);
                    fresh.getFoodData().setFoodLevel(7);
                    fresh.getPersistentData().putInt("test:fresh", 99);
                    fresh.setData(EXTRA.get(), 88);
                    fresh.addTag("new-life");
                } else {
                    helper.assertValueEqual(fresh.getData(EXTRA.get()), 88, "NeoForge default copying does not replace listener initialization");
                    fresh.setData(EXTRA.get(), 89);
                }
            });
            ReincarnationRequest request = new ReincarnationRequest(active.level().dimension(), active.position(), 0, 0,
                    Component.literal("Fresh initialization"), new ResetPolicy(true, false, true, true));
            active = service.reincarnate(active, request);
            helper.assertValueEqual(phases, List.of("clone", "respawn"), "initialization follows normal events");
            helper.assertValueEqual(active.getData(EXTRA.get()), 89, "respawn listener attachment survives finalization");
            assertInitialization(helper, active);

            FAIL.add(account);
            try {
                service.reincarnate(active, request);
                helper.fail("initialization recovery fixture did not fail");
            } catch (IllegalStateException _) {
            }
            GameProfile profile = active.getGameProfile();
            remove(active.connection.player);
            ReincarnationService.stop();
            ReincarnationService.start(server);
            service = ReincarnationService.get();
            phases.clear();
            active = connect(helper, profile);
            helper.assertValueEqual(phases, List.of("login"), "recovery initializes the unloaded login player once");
            helper.assertTrue(service.pending(account).isEmpty(), "initialized recovery commits");
            helper.assertValueEqual(active.getData(EXTRA.get()), 88, "login listener attachment survives recovery");
            assertInitialization(helper, active);
            helper.succeed();
        } finally {
            OBSERVERS.remove(account);
            FAIL.remove(account);
            RECOVERED.remove(account);
            ServerPlayer online = server.getPlayerList().getPlayer(account);
            if (online != null) remove(online);
        }
    }

    private static void assertInitialization(GameTestHelper helper, ServerPlayer player) {
        helper.assertValueEqual(player.getInventory().getItem(0).getCount(), 3, "new inventory initialization is preserved");
        helper.assertValueEqual(player.getEnderChestInventory().getItem(0).getCount(), 2, "new ender chest initialization is preserved");
        helper.assertTrue(player.totalExperience == 17 && player.getMaxHealth() == 40 && player.getHealth() == 35
                && player.getFoodData().getFoodLevel() == 7, "new gameplay initialization is preserved");
        helper.assertTrue(player.entityTags().equals(Set.of("new-life")), "new tags survive without old tags");
        helper.assertTrue(!player.getPersistentData().contains("test:old") && player.getPersistentData().getInt("test:fresh").orElseThrow() == 99,
                "resetUnknownModData omits old NBT and preserves new NBT");
        CompoundTag stored = player.level().getServer().getPlayerList().loadPlayerData(player.nameAndId()).orElseThrow();
        helper.assertValueEqual(stored.getInt("XpTotal").orElseThrow(), 17, "player file contains listener initialization");
        helper.assertValueEqual(stored.getCompound("NeoForgeData").orElseThrow().getInt("test:fresh").orElseThrow(), 99, "new mod NBT is persisted");
    }

    private static void cancel(GameTestHelper helper) {
        ServerPlayer player = connect(helper);
        ReincarnationService service = ReincarnationService.get();
        try {
            CANCEL.add(player.getUUID());
            REENTER.add(player.getUUID());
            try {
                service.reincarnate(player, request(player));
                helper.fail("canceled reincarnation accepted");
            } catch (IllegalStateException _) {
            }
            helper.assertValueEqual(Generations.current(helper.getLevel().getServer(), player.getUUID()), 0L, "cancel does not advance generation");
            helper.assertTrue(service.pending(player.getUUID()).isEmpty() && service.store().history(player.getUUID()).isEmpty(), "cancel publishes no history");
            helper.assertTrue(helper.getLevel().getServer().getPlayerList().getPlayer(player.getUUID()) == player, "cancel preserves live player");
            helper.succeed();
        } finally {
            remove(player);
        }
    }

    private static void recovery(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ReincarnationService service = ReincarnationService.get();
        ServerPlayer old = connect(helper);
        GameProfile profile = old.getGameProfile();
        old.getPersistentData().putInt("test:kept", 81);
        FAIL.add(old.getUUID());
        try {
            service.reincarnate(old, request(old));
            helper.fail("handler failure accepted");
        } catch (IllegalStateException _) {
        }
        LifeTransaction tx = service.pending(old.getUUID()).orElseThrow();
        helper.assertValueEqual(tx.state(), LifeTransaction.State.COMMITTING, "failure remains pending");
        Messages messages = new Messages();
        try {
            server.getCommands().getDispatcher().execute("reincarnation status " + old.getUUID(), source(helper, messages, old, true));
        } catch (CommandSyntaxException failure) {
            throw new IllegalStateException(failure);
        }
        Object[] pending = message(messages, "command.reincarnation.pending").getArgs();
        helper.assertValueEqual(pending[1], "1", "pending source uses the visible first life");
        helper.assertValueEqual(pending[2], "2", "pending destination uses the visible second life");
        ServerPlayer partial = server.getPlayerList().getPlayer(old.getUUID());
        // EmbeddedChannel executes disconnect/removal synchronously; an actual socket may remove next tick.
        helper.assertTrue(!service.canSave(partial == null ? old : partial), "partial player saves blocked");
        if (partial != null) remove(partial);
        // Reopen the coordinator to exercise disk transaction discovery, then go through the actual login event.
        ReincarnationService.stop();
        ReincarnationService.start(server);
        service = ReincarnationService.get();
        helper.assertTrue(server.getPlayerList().loadPlayerData(old.nameAndId()).isEmpty(), "old player data suppressed before constructor");
        ServerPlayer fresh = connect(helper, profile);
        try {
            helper.assertTrue(service.pending(fresh.getUUID()).isEmpty(), "login completed pending transaction");
            helper.assertValueEqual(RECOVERED.remove(fresh.getUUID()), tx.id(), "handler recovery uses original transaction");
            helper.assertValueEqual(Generations.current(server, fresh.getUUID()), 1L, "recovery does not increment again");
            helper.assertValueEqual(fresh.getPersistentData().getInt("test:kept").orElseThrow(), 81, "saved handler data restored");
            helper.assertValueEqual(service.store().history(fresh.getUUID()).size(), 1, "one archived life");
            helper.succeed();
        } finally {
            remove(fresh);
        }
    }

    private static void finalizeWithoutReset(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ReincarnationService service = ReincarnationService.get();
        ServerPlayer old = connect(helper);
        ServerPlayer fresh = service.reincarnate(old, request(old));
        try {
            LifeTransaction tx = service.store().transactions().stream().filter(value -> value.account().equals(fresh.getUUID())).findFirst().orElseThrow();
            fresh.getInventory().setItem(0, new ItemStack(Items.NETHER_STAR, 3));
            fresh.giveExperiencePoints(120);
            fresh.getStats().setValue(fresh, Stats.CUSTOM.get(Stats.DEATHS), 4);
            server.getPlayerList().getPlayerIo().save(fresh);
            fresh.getStats().save();
            service.store().transaction(tx.withState(LifeTransaction.State.COMMITTING));
            ReincarnationService.stop();
            ReincarnationService.start(server);
            service = ReincarnationService.get();
            helper.assertTrue(service.pending(fresh.getUUID()).isEmpty(), "already initialized life finalized from disk");
            CompoundTag data = server.getPlayerList().loadPlayerData(fresh.nameAndId()).orElseThrow();
            helper.assertValueEqual(data.getInt("XpTotal").orElseThrow(), fresh.totalExperience, "new-life experience preserved during finalization");
            helper.assertTrue(((ProgressAccess) fresh.getStats()).reincarnation$json().contains("minecraft:deaths\":4"), "new-life statistics preserved");
            helper.assertValueEqual(fresh.getInventory().getItem(0).getCount(), 3, "live new-life inventory not reset");
            helper.assertValueEqual(service.store().history(fresh.getUUID()).size(), 1, "history finalization is idempotent");
            // A legacy/fallback record must never silently attach to generation one.
            try {
                service.validateLoad(fresh.getUUID(), Optional.of(new CompoundTag()));
                helper.fail("old data accepted");
            } catch (IllegalStateException _) {
            }
            GameProfile profile = fresh.getGameProfile();
            remove(fresh);
            ServerPlayer rejoined = connect(helper, profile);
            try {
                helper.assertValueEqual(rejoined.getInventory().getItem(0).getCount(), 3, "new-life inventory survives actual player reconstruction and load");
                helper.assertValueEqual(rejoined.getStats().getValue(Stats.CUSTOM.get(Stats.DEATHS)), 4, "new-life stats reload from disk");
                helper.assertValueEqual(LifeMarker.of(rejoined).getLong("generation").orElseThrow(), 1L, "generation marker survives logout/login");
            } finally {
                remove(rejoined);
            }
            helper.succeed();
        } finally {
            remove(fresh);
        }
    }

    private static void intentReplay(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ReincarnationService service = ReincarnationService.get();
        ServerPlayer old = connect(helper);
        UUID id = UUID.randomUUID();
        long now = System.currentTimeMillis();
        CompoundTag archive = new CompoundTag();
        archive.putInt("version", 1);
        archive.putString("transaction", id.toString());
        archive.put("player", ReincarnationService.playerData(old));
        archive.putString("stats", ((ProgressAccess) old.getStats()).reincarnation$json());
        archive.putString("advancements", ((AdvancementAccess) old.getAdvancements()).reincarnation$json());
        archive.put("handlers", Handlers.capture(old));
        archive.put("record", SoulRecord.capture(old, 0, now, now, Component.literal("意图恢复"), id.toString()).save());
        LifeTransaction tx = new LifeTransaction(id, old.getUUID(), old.getGameProfile().name(), 0, 1, now, request(old), service.store().archive(id, archive), LifeTransaction.State.COMMITTING);
        service.store().transaction(tx);
        UUID preparedId = UUID.randomUUID(), preparedAccount = UUID.randomUUID();
        LifeTransaction prepared = new LifeTransaction(preparedId, preparedAccount, "PreparedOnly", 0, 1, now, request(old), "not-used", LifeTransaction.State.PREPARED);
        service.store().transaction(prepared);
        remove(old); // Simulate a crash after durable intent but before the index update.
        helper.assertValueEqual(Generations.current(server, old.getUUID()), 0L, "index not yet advanced at simulated crash");
        ReincarnationService.stop();
        ReincarnationService.start(server);
        service = ReincarnationService.get();
        helper.assertValueEqual(Generations.current(server, old.getUUID()), 1L, "startup applies durable commit intent");
        helper.assertValueEqual(Generations.current(server, preparedAccount), 0L, "prepare-only transaction does not advance");
        helper.assertValueEqual(FileAccess.NBT.load(LifeTransaction.CODEC, service.store().transactionFile(preparedId)).orElseThrow().state(),
                LifeTransaction.State.ABORTED, "prepare-only transaction aborted");
        ServerPlayer fresh = connect(helper, old.getGameProfile());
        try {
            helper.assertValueEqual(LifeMarker.of(fresh).getString("transaction").orElseThrow(), id.toString(), "replay retains transaction identity");
            helper.assertValueEqual(RECOVERED.remove(fresh.getUUID()), id, "handler receives original intent");
            helper.assertTrue(service.pending(fresh.getUUID()).isEmpty(), "intent replay completed");
            helper.succeed();
        } finally {
            remove(fresh);
        }
    }

    private static void ioFailure(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ReincarnationService service = ReincarnationService.get();
        ServerPlayer old = connect(helper);
        Path path = server.getWorldPath(LevelResource.PLAYER_STATS_DIR).resolve(old.getUUID() + ".json");
        try {
            Files.createDirectories(path); // A real filesystem replacement failure, no mock writer.
            try {
                service.reincarnate(old, request(old));
                helper.fail("IO failure accepted");
            } catch (IllegalStateException _) {
            }
            LifeTransaction tx = service.pending(old.getUUID()).orElseThrow();
            helper.assertTrue(service.store().history(old.getUUID()).isEmpty(), "failed writes publish no completed history");
            helper.assertTrue(!service.canSave(old), "old-life autosave blocked after commit intent");
            remove(old);
            Files.delete(path);
            helper.assertTrue(server.getPlayerList().loadPlayerData(old.nameAndId()).isEmpty(), "recovery load starts empty");
            ServerPlayer fresh = connect(helper, old.getGameProfile());
            try {
                helper.assertValueEqual(RECOVERED.remove(fresh.getUUID()), tx.id(), "IO failure replays same transaction");
                helper.assertTrue(service.pending(fresh.getUUID()).isEmpty(), "IO failure recovered");
            } finally {
                remove(fresh);
            }
            helper.succeed();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void policy(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ReincarnationService service = ReincarnationService.get();
        ServerPlayer old = connect(helper);
        ServerPlayer active = old;
        try {
            old.setGameMode(GameType.CREATIVE);
            old.getPersistentData().putString("thirdparty:unknown", "remove");
            old.getPersistentData().putInt("test:kept", 93);
            old.setData(EXTRA.get(), 99);
            PlayerTeam team = server.getScoreboard().addPlayerTeam("p_" + old.getId());
            server.getScoreboard().addPlayerToTeam(old.getScoreboardName(), team);
            ReincarnationRequest base = request(old);
            active = service.reincarnate(old, new ReincarnationRequest(Level.NETHER,
                    new Vec3(0.5, 100, 0.5), 0, 0, Component.literal("清理策略"), new ResetPolicy(false, true, true, false)));
            helper.assertTrue(!active.getPersistentData().contains("thirdparty:unknown") && active.getData(EXTRA.get()) == 0, "explicit policy clears unknown NBT and attachments");
            helper.assertValueEqual(active.getPersistentData().getInt("test:kept").orElseThrow(), 93, "registered handler can explicitly retain its own data");
            helper.assertTrue(active.getTeam() == null && active.gameMode.getGameModeForPlayer() == server.getDefaultGameType(), "team and game mode policy applied");
            helper.assertTrue(LifeMarker.of(active).getLong("generation").orElseThrow() == 1, "life marker installed after mod reset policy");
            helper.assertTrue(active.level().dimension() == Level.NETHER, "configured destination dimension applied");
            server.getScoreboard().removePlayerTeam(team);
            helper.succeed();
        } finally {
            remove(active);
        }
    }

    private static ServerPlayer dieAndRespawn(GameTestHelper helper, ServerPlayer player) {
        player.hasChangedDimension();
        player.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
        player.setGameMode(GameType.SURVIVAL);
        player.hurtServer(player.level(), player.damageSources().genericKill(), Float.MAX_VALUE);
        helper.assertTrue(player.isDeadOrDying(), "real lethal damage kills the player before the respawn request");
        player.connection.handleClientCommand(new ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));
        return player.connection.player;
    }

    private static void death(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ReincarnationService service = ReincarnationService.get();
        ReincarnationServerConfig config = ReincarnationServerConfig.INSTANCE;
        boolean automatic = config.lifecycle.autoReincarnateOnDeath.getValue();
        boolean keep = helper.getLevel().getGameRules().get(GameRules.KEEP_INVENTORY);
        ServerPlayer active = connect(helper);
        try {
            helper.getLevel().getGameRules().set(GameRules.KEEP_INVENTORY, true, server);
            config.lifecycle.autoReincarnateOnDeath.setValue(false);
            active.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 13));
            active.getEnderChestInventory().setItem(0, new ItemStack(Items.EMERALD, 7));
            active.giveExperiencePoints(250);
            active = dieAndRespawn(helper, active);
            helper.assertValueEqual(Generations.current(server, active.getUUID()), 0L, "disabled setting preserves ordinary death behavior");
            helper.assertValueEqual(active.getInventory().getItem(0).getCount(), 13, "ordinary death respects keepInventory");
            helper.assertTrue(service.store().history(active.getUUID()).isEmpty(), "ordinary death creates no life archive");

            config.lifecycle.autoReincarnateOnDeath.setValue(true);
            active.wonGame = true;
            active.connection.handleClientCommand(new ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));
            active = active.connection.player;
            helper.assertValueEqual(Generations.current(server, active.getUUID()), 0L, "End return does not reincarnate a living player");
            helper.assertValueEqual(active.getEnderChestInventory().getItem(0).getCount(), 7, "End return preserves player progress");

            EntityReference<LivingEntity> previousLife = EntityReference.of(active);
            active = dieAndRespawn(helper, active);
            helper.assertValueEqual(Generations.current(server, active.getUUID()), 1L, "enabled setting advances generation once on death respawn");
            Messages messages = new Messages();
            EmbeddedChannel channel = (EmbeddedChannel) active.connection.getConnection().channel();
            Object packet;
            while ((packet = channel.readOutbound()) != null)
                if (packet instanceof ClientboundSystemChatPacket chat) messages.lines.add(chat.content());
            helper.assertValueEqual(message(messages, "command.reincarnation.rebirth.success").getArgs()[1], "2", "automatic reincarnation announces the second life");
            helper.assertTrue(active.getInventory().isEmpty() && active.getEnderChestInventory().isEmpty() && active.totalExperience == 0, "automatic reincarnation performs the full reset even with keepInventory");
            helper.assertValueEqual(active.getStats().getValue(Stats.CUSTOM.get(Stats.DEATHS)), 0, "new life starts with no deaths");
            SoulRecord archive = service.store().history(active.getUUID(), 0).orElseThrow();
            helper.assertValueEqual(archive.deaths(), 2, "old-life archive includes the triggering death");
            helper.assertTrue(archive.inventoryItems() >= 13, "death archive includes items retained by vanilla keepInventory");
            helper.assertValueEqual(archive.reason(), Component.translatable("reason.reincarnation.death"), "automatic reason remains translatable in history");
            helper.assertTrue(!LifeReferences.isCurrent(previousLife, active), "previous-life references expire after a death reincarnation");
            active.connection.handleClientCommand(new ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));
            helper.assertValueEqual(Generations.current(server, active.getUUID()), 1L, "duplicate respawn packet does not create another generation");

            active = dieAndRespawn(helper, active);
            helper.assertValueEqual(Generations.current(server, active.getUUID()), 2L, "the next death advances exactly one more generation");
            helper.assertValueEqual(service.store().history(active.getUUID()).size(), 2, "successive deaths create one archive each");
            CANCEL.add(active.getUUID());
            active = dieAndRespawn(helper, active);
            helper.assertTrue(active.isAlive() && Generations.current(server, active.getUUID()) == 2, "Pre cancellation falls back to ordinary respawn");
            helper.assertTrue(service.pending(active.getUUID()).isEmpty() && service.store().history(active.getUUID()).size() == 2, "canceled death reincarnation creates no transaction or archive");

            FAIL.add(active.getUUID());
            active = dieAndRespawn(helper, active);
            LifeTransaction tx = service.pending(active.getUUID()).orElseThrow();
            helper.assertValueEqual(tx.to(), 3L, "failed death reincarnation retains the original target generation");
            helper.assertTrue(!service.canSave(active), "partial death reincarnation cannot overwrite the save");
            helper.assertTrue(server.getPlayerList().respawn(active, false, Entity.RemovalReason.KILLED) == active, "pending recovery blocks another vanilla respawn");
            GameProfile profile = active.getGameProfile();
            remove(active);
            config.lifecycle.autoReincarnateOnDeath.setValue(false);
            ReincarnationService.stop();
            ReincarnationService.start(server);
            service = ReincarnationService.get();
            active = connect(helper, profile);
            helper.assertTrue(service.pending(active.getUUID()).isEmpty(), "relogin recovers an interrupted death reincarnation even after disabling the setting");
            helper.assertValueEqual(Generations.current(server, active.getUUID()), 3L, "death recovery does not increment generation again");
            helper.assertValueEqual(RECOVERED.remove(active.getUUID()), tx.id(), "death recovery replays the same transaction");
            helper.assertValueEqual(service.store().history(active.getUUID()).size(), 3, "death recovery publishes exactly one additional archive");
            helper.succeed();
        } finally {
            config.lifecycle.autoReincarnateOnDeath.setValue(automatic);
            helper.getLevel().getGameRules().set(GameRules.KEEP_INVENTORY, keep, server);
            remove(active);
        }
    }

    private static void delayedDeath(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ReincarnationServerConfig config = ReincarnationServerConfig.INSTANCE;
        ServerPlayer dead = connect(helper);
        try {
            dead.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
            dead.setGameMode(GameType.SURVIVAL);
            dead.getEnderChestInventory().setItem(0, new ItemStack(Items.EMERALD, 7));
            server.getCommands().getDispatcher().execute("kill " + dead.getGameProfile().name(), server.createCommandSourceStack());
            helper.assertTrue(dead.isDeadOrDying(), "the actual kill command kills the player");
            // Embedded connections are outside the server's network listener, which normally ticks the player.
            for (int tick = 1; tick <= 25; tick++) helper.runAfterDelay(tick, dead.connection::tick);
            helper.runAfterDelay(35, () -> {
                boolean automatic = config.lifecycle.autoReincarnateOnDeath.getValue();
                config.lifecycle.autoReincarnateOnDeath.setValue(true);
                try {
                    helper.assertTrue(dead.isRemoved() && dead.getRemovalReason() == Entity.RemovalReason.KILLED, "vanilla removes the corpse while the death screen is open");
                    helper.assertTrue(server.getPlayerList().getPlayer(dead.getUUID()) == dead && dead.connection.getConnection().isConnected(), "the corpse still belongs to the active online session");
                    dead.connection.handleClientCommand(new ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));
                    ServerPlayer fresh = dead.connection.player;
                    ReincarnationService service = ReincarnationService.get();
                    helper.assertValueEqual(Generations.current(server, fresh.getUUID()), 1L, "delayed death-screen respawn completes reincarnation");
                    helper.assertTrue(fresh != dead && fresh.isAlive() && fresh.getEnderChestInventory().isEmpty(), "removed corpse is replaced by a fully reset new-life player");
                    SoulRecord archive = service.store().history(fresh.getUUID(), 0).orElseThrow();
                    helper.assertValueEqual(archive.deaths(), 1, "delayed respawn archives the triggering death");
                    helper.assertValueEqual(archive.reason(), Component.translatable("reason.reincarnation.death"), "delayed respawn records a death reincarnation");
                    rejectOffline(helper, service, dead, "old corpse is rejected once the session uses a replacement");
                    helper.assertTrue(service.canSave(fresh) && !service.canSave(dead) && service.pending(fresh.getUUID()).isEmpty(), "only the completed new life can save");
                    helper.assertValueEqual(service.store().history(fresh.getUUID()).size(), 1, "delayed respawn creates exactly one archive");
                    ServerPlayer discarded = connect(helper);
                    try {
                        discarded.discard();
                        rejectOffline(helper, service, discarded, "a player removed for a reason other than death remains rejected");
                    } finally {
                        remove(discarded);
                    }
                    ServerPlayer disconnected = connect(helper);
                    try {
                        disconnected.connection.getConnection().disconnect(Component.literal("Test disconnection"));
                        rejectOffline(helper, service, disconnected, "a closed connection is rejected even before the player-list entry is removed");
                    } finally {
                        remove(disconnected);
                    }
                    helper.succeed();
                } finally {
                    config.lifecycle.autoReincarnateOnDeath.setValue(automatic);
                    remove(dead.connection.player);
                }
            });
        } catch (CommandSyntaxException failure) {
            remove(dead);
            throw new IllegalStateException(failure);
        } catch (RuntimeException | Error failure) {
            remove(dead);
            throw failure;
        }
    }

    private static void rejectOffline(GameTestHelper helper, ReincarnationService service, ServerPlayer player, String message) {
        try {
            service.reincarnate(player, request(player));
            helper.fail(message);
        } catch (ReincarnationException failure) {
            helper.assertValueEqual(((TranslatableContents) failure.component().getContents()).getKey(), "error.reincarnation.offline", message);
        }
    }

    private static void configuration(GameTestHelper helper) {
        ReincarnationServerConfig config = ReincarnationServerConfig.INSTANCE;
        String original = config.serialize();
        ServerPlayer player = connect(helper);
        try {
            helper.assertTrue(ConfigManager.getInstance().getConfigs().contains(config), "configuration is registered and initialized by Jupiter");
            helper.assertTrue(ServerConfigManager.getConfig(config.getConfigId()) == config, "configuration is registered for server reload and remote editing");
            config.deserialize("""
                    {"lifespan":{"enabled":true,"base_lifespan":2400,"on_expire":"REINCARNATE",
                     "player_death_mode":"SPECTATOR","player_rebirth_mode":"RESET"}}
                    """);
            helper.assertTrue(config.lifespan.enabled.getValue() && config.lifespan.baseLifespan.getValue() == 2400,
                    "Jupiter exposes and loads the new lifespan category");
            helper.assertValueEqual(config.lifespan.onExpire.getValue(), ReincarnationServerConfig.LifespanOutcome.REINCARNATE,
                    "expiry setting decodes Jupiter enum names");
            helper.assertValueEqual(config.lifespan.playerDeathMode.getValue(), ReincarnationServerConfig.PlayerDeathMode.SPECTATOR,
                    "player death setting decodes through Jupiter");
            helper.assertValueEqual(config.lifespan.playerRebirthMode.getValue(), com.iafenvoy.reincarnation.api.RebirthMode.RESET,
                    "rebirth mode setting decodes through Jupiter");
            helper.assertTrue(config.serialize().contains("\"lifespan\""), "lifespan settings are serialized with the other categories");
            config.deserialize(original);
            config.deserialize("""
                    {"lifecycle":{"auto_reincarnate_on_death":true,"filter_death_damage_types":true},
                     "reset":{"scores":false,"team":true,"unknown_mod_data":true,"keep_game_mode":false},
                     "query":{"history_page_size":3}}
                    """);
            ReincarnationRequest request = ReincarnationRequest.atSpawn(player, Component.literal("配置测试"));
            helper.assertTrue(!config.lifecycle.autoReincarnateOnDeath.getDefaultValue() && config.lifecycle.autoReincarnateOnDeath.getValue(), "automatic death reincarnation defaults to disabled and can be enabled through JSON");
            helper.assertTrue(!config.lifecycle.filterDeathDamageTypes.getDefaultValue() && config.lifecycle.filterDeathDamageTypes.getValue(),
                    "lethal damage filtering defaults to disabled and can be enabled through Jupiter JSON");
            ResetPolicy configured = new ResetPolicy(false, true, true, false);
            helper.assertValueEqual(request.policy(), configured, "spawn requests snapshot all configured reset choices");
            Tag transaction = LifeTransaction.CODEC.encodeStart(NbtOps.INSTANCE, new LifeTransaction(UUID.randomUUID(), player.getUUID(), player.getGameProfile().name(), 0, 1, System.currentTimeMillis(), request, "unused", LifeTransaction.State.COMMITTING)).getOrThrow();
            config.deserialize("""
                    {"reset":{"scores":true,"team":false,"unknown_mod_data":false,"keep_game_mode":true}}
                    """);
            helper.assertValueEqual(ReincarnationRequest.atSpawn(player, Component.literal("配置更新")).policy(), ResetPolicy.DEFAULT, "later requests use the updated settings");
            helper.assertValueEqual(LifeTransaction.CODEC.parse(NbtOps.INSTANCE, transaction).getOrThrow().request().policy(), configured, "recovery retains the saved policy after configuration changes");
            helper.assertValueEqual(request.policy(), configured, "existing requests remain immutable");
            config.deserialize("""
                    {"query":{"history_page_size":0}}
                    """);
            helper.assertValueEqual(config.query.historyPageSize.getValue(), 3, "out-of-range page size is rejected during config decoding");
            config.deserialize("""
                    {"query":{"history_page_size":51}}
                    """);
            helper.assertValueEqual(config.query.historyPageSize.getValue(), 3, "page size above the limit is rejected");
            helper.succeed();
        } finally {
            config.deserialize(original);
            remove(player);
        }
    }

    private static final class Messages implements CommandSource {
        final List<Component> lines = new ArrayList<>();

        @Override
        public void sendSystemMessage(@NonNull Component message) {
            this.lines.add(message);
        }

        @Override
        public boolean acceptsSuccess() {
            return true;
        }

        @Override
        public boolean acceptsFailure() {
            return true;
        }

        @Override
        public boolean shouldInformAdmins() {
            return false;
        }
    }

    private static TranslatableContents message(Messages messages, String key) {
        return messages.lines.stream().map(Component::getContents).filter(TranslatableContents.class::isInstance)
                .map(TranslatableContents.class::cast).filter(content -> content.getKey().equals(key)).findFirst().orElseThrow();
    }

    private static CommandSourceStack source(GameTestHelper helper, Messages messages, ServerPlayer player, boolean admin) {
        return new CommandSourceStack(messages, player.position(), Vec2.ZERO, helper.getLevel(), admin ? PermissionSet.ALL_PERMISSIONS : PermissionSet.NO_PERMISSIONS,
                player.getGameProfile().name(), player.getName(), helper.getLevel().getServer(), player);
    }
}
