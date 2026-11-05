package com.iafenvoy.reincarnation.gametest;

import com.iafenvoy.reincarnation.api.ReincarnationContext;
import com.iafenvoy.reincarnation.api.ReincarnationHandler;
import com.iafenvoy.reincarnation.api.ReincarnationRequest;
import com.iafenvoy.reincarnation.api.ResetPolicy;
import com.iafenvoy.reincarnation.config.ReincarnationServerConfig;
import com.iafenvoy.reincarnation.event.RegisterReincarnationHandlersEvent;
import com.iafenvoy.reincarnation.lifecycle.LifeTransaction;
import com.iafenvoy.reincarnation.lifecycle.ReincarnationService;
import com.iafenvoy.reincarnation.registry.tag.AttachmentTypeTags;
import com.mojang.authlib.GameProfile;
import com.mojang.serialization.Codec;
import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.tags.TagLoader;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentHolder;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.Supplier;

final class AttachmentTests {
    private static final Map<UUID, Integer> COPIES = new HashMap<>();
    private static final DeferredRegister<AttachmentType<?>> TYPES = DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, ReferenceTests.MOD_ID);
    private static final Supplier<AttachmentType<Integer>> TAG_ONLY = TYPES.register("gametest/tag_only", () -> AttachmentType.builder(() -> 0).serialize(Codec.INT.fieldOf("value")).build());
    private static final Supplier<AttachmentType<Integer>> COPY_ONLY = TYPES.register("gametest/copy_only", () -> AttachmentType.builder(() -> 0).serialize(Codec.INT.fieldOf("value")).copyOnDeath().build());
    private static final Supplier<AttachmentType<Integer>> KEPT = TYPES.register("gametest/kept", () -> AttachmentType.builder(() -> 0).serialize(Codec.INT.fieldOf("value")).copyOnDeath().build());
    private static final Supplier<AttachmentType<Integer>> VETOED = TYPES.register("gametest/vetoed", () -> AttachmentType.builder(() -> 0).serialize(Codec.INT.fieldOf("value")).copyOnDeath().copyHandler((value, holder, provider) -> null).build());
    private static final Supplier<AttachmentType<Integer>> TRANSFORMED = TYPES.register("gametest/transformed", () -> AttachmentType.builder(() -> 0).serialize(Codec.INT.fieldOf("value")).copyOnDeath().copyHandler((value, holder, provider) -> {
        if (holder instanceof ServerPlayer player) COPIES.computeIfPresent(player.getUUID(), (_, count) -> count + 1);
        return value + 1;
    }).build());
    private static final Set<UUID> FAIL = new HashSet<>();
    private static final Map<UUID, List<String>> ORDER = new HashMap<>();
    private static final Map<UUID, Consumer<ServerPlayer>> CHECKS = new HashMap<>();

    static Map<String, Consumer<GameTestHelper>> tests() {
        return Map.of("attachment_retention", AttachmentTests::retention, "lifecycle_event_order", AttachmentTests::eventOrder);
    }

    static void register(IEventBus bus) {
        TYPES.register(bus);
        bus.addListener((RegisterReincarnationHandlersEvent event) -> event.register(new ReincarnationHandler() {
            @Override
            public Identifier id() {
                return Identifier.fromNamespaceAndPath(ReferenceTests.MOD_ID, "gametest/attachment_handler");
            }

            @Override
            public CompoundTag archive(ServerPlayer player) {
                record(player.getUUID(), "archive");
                return new CompoundTag();
            }

            @Override
            public void reset(ServerPlayer player, ReincarnationContext context, CompoundTag data) {
                if (FAIL.remove(player.getUUID()))
                    throw new IllegalStateException("Intentional attachment-test failure");
                record(player.getUUID(), "reset");
                if (ORDER.containsKey(player.getUUID()))
                    player.getPersistentData().putString("test:event_order", String.join(",", ORDER.get(player.getUUID())));
            }
        }));
        NeoForge.EVENT_BUS.addListener(EventPriority.LOW, (PlayerEvent.Clone event) -> {
            record(event.getEntity().getUUID(), "clone");
            check(event.getEntity());
        });
        NeoForge.EVENT_BUS.addListener(EventPriority.LOW, (PlayerEvent.PlayerRespawnEvent event) -> {
            record(event.getEntity().getUUID(), "respawn");
            check(event.getEntity());
        });
        NeoForge.EVENT_BUS.addListener(EventPriority.LOW, (PlayerEvent.PlayerLoggedInEvent event) -> record(event.getEntity().getUUID(), "login"));
    }

    private static void check(Player player) {
        Consumer<ServerPlayer> check = CHECKS.get(player.getUUID());
        if (check != null && player instanceof ServerPlayer serverPlayer) check.accept(serverPlayer);
    }

    private static void record(UUID account, String name) {
        List<String> order = ORDER.get(account);
        if (order != null) order.add(name);
    }

    private static ReincarnationRequest request(ServerPlayer player) {
        return new ReincarnationRequest(player.level().dimension(), player.position(), 0, 0, Component.literal("Attachment test"), ResetPolicy.DEFAULT);
    }

    private static Map<TagKey<AttachmentType<?>>, List<Holder<AttachmentType<?>>>> tags() {
        Map<TagKey<AttachmentType<?>>, List<Holder<AttachmentType<?>>>> tags = new HashMap<>();
        NeoForgeRegistries.ATTACHMENT_TYPES.getTags().forEach(tag -> tags.put(tag.key(), tag.stream().toList()));
        return tags;
    }

    private static void tags(Map<TagKey<AttachmentType<?>>, List<Holder<AttachmentType<?>>>> tags) {
        NeoForgeRegistries.ATTACHMENT_TYPES.prepareTagReload(new TagLoader.LoadResult<>(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, tags)).apply();
    }

    private static void keep(AttachmentType<?>... types) {
        Map<TagKey<AttachmentType<?>>, List<Holder<AttachmentType<?>>>> tags = tags();
        tags.put(AttachmentTypeTags.KEEP_ON_REBIRTH, Arrays.stream(types).map(NeoForgeRegistries.ATTACHMENT_TYPES::wrapAsHolder).toList());
        tags(tags);
    }

    private static void seed(ServerPlayer player) {
        player.setData(TAG_ONLY.get(), 10);
        player.setData(COPY_ONLY.get(), 20);
        player.setData(KEPT.get(), 30);
        player.setData(VETOED.get(), 40);
        player.setData(TRANSFORMED.get(), 50);
    }

    private static ServerPlayer interruptAndRecover(GameTestHelper helper, ServerPlayer active, Runnable beforeRecovery) {
        MinecraftServer server = helper.getLevel().getServer();
        ReincarnationService service = ReincarnationService.get();
        FAIL.add(active.getUUID());
        try {
            service.reincarnate(active, request(active));
            helper.fail("interrupted transaction fixture did not fail");
        } catch (IllegalStateException _) {
        }
        helper.assertTrue(service.pending(active.getUUID()).isPresent(), "fixture left a durable pending transaction");
        GameProfile profile = active.getGameProfile();
        LifecycleTests.remove(active.connection.player);
        beforeRecovery.run();
        ReincarnationService.stop();
        ReincarnationService.start(server);
        return LifecycleTests.connect(helper, profile);
    }

    private static void retention(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ReincarnationService service = ReincarnationService.get();
        Map<TagKey<AttachmentType<?>>, List<Holder<AttachmentType<?>>>> originalTags = tags();
        boolean automatic = ReincarnationServerConfig.INSTANCE.lifecycle.autoReincarnateOnDeath.getValue();
        ServerPlayer active = LifecycleTests.connect(helper);
        UUID account = active.getUUID();
        try {
            ReincarnationServerConfig.INSTANCE.lifecycle.autoReincarnateOnDeath.setValue(false);
            helper.assertValueEqual(NeoForgeRegistries.ATTACHMENT_TYPES.get(AttachmentTypeTags.KEEP_ON_REBIRTH).orElseThrow().size(), 0, "default retention tag loads from the data pack and is empty");
            seed(active);
            COPIES.put(account, 0);
            CHECKS.put(account, fresh -> helper.assertTrue(!fresh.hasData(COPY_ONLY.get()) && !fresh.hasData(TRANSFORMED.get()),
                    "untagged attachments are never copied, including during Clone and Respawn"));
            active = service.reincarnate(active, request(active));
            CHECKS.remove(account);
            helper.assertValueEqual(COPIES.get(account), 0, "excluded attachment copy handlers never run");
            helper.assertTrue(!active.hasData(TAG_ONLY.get()) && !active.hasData(COPY_ONLY.get()) && !active.hasData(KEPT.get())
                    && !active.hasData(VETOED.get()) && !active.hasData(TRANSFORMED.get()), "an empty tag clears all attachments, even copyOnDeath attachments");
            LifeTransaction first = service.store().transactions().stream().filter(tx -> tx.account().equals(account)).findFirst().orElseThrow();
            CompoundTag saved = service.store().archive(first).getCompound("player").orElseThrow().getCompound(AttachmentHolder.ATTACHMENTS_NBT_KEY).orElseThrow();
            helper.assertValueEqual(saved.getCompound(NeoForgeRegistries.ATTACHMENT_TYPES.getKey(TAG_ONLY.get()).toString()).orElseThrow().getInt("value").orElseThrow(),
                    10, "cleared data remains intact in the old-life archive");

            active.setData(COPY_ONLY.get(), 21);
            active = server.getPlayerList().respawn(active, false, Entity.RemovalReason.CHANGED_DIMENSION);
            active.connection.player = active;
            helper.assertValueEqual(active.getData(COPY_ONLY.get()), 21, "ordinary respawn honors vanilla copying independently of the tag");

            keep(TAG_ONLY.get(), KEPT.get(), VETOED.get(), TRANSFORMED.get());
            seed(active);
            CHECKS.put(account, fresh -> {
                helper.assertTrue(!fresh.hasData(TAG_ONLY.get()) && !fresh.hasData(COPY_ONLY.get()) && !fresh.hasData(VETOED.get()),
                        "attachment exclusions already hold during Clone and Respawn");
                helper.assertValueEqual(fresh.getData(TRANSFORMED.get()), 51, "retained data is available to Clone and Respawn listeners");
            });
            active = service.reincarnate(active, request(active));
            helper.assertValueEqual(COPIES.get(account), 1, "retained attachment copy handler runs exactly once per transition");
            helper.assertTrue(!active.hasData(TAG_ONLY.get()) && !active.hasData(COPY_ONLY.get()), "tag alone and copyOnDeath alone both fail retention");
            helper.assertValueEqual(active.getData(KEPT.get()), 30, "tag plus copyOnDeath retains archived data");
            helper.assertTrue(!active.hasData(VETOED.get()), "custom copy handler can veto retention");
            helper.assertValueEqual(active.getData(TRANSFORMED.get()), 51, "retention invokes the custom copy handler on archived data");

            seed(active);
            COPIES.put(account, 0);
            active = interruptAndRecover(helper, active, () -> {
                LifeTransaction pending = ReincarnationService.get().pending(account).orElseThrow();
                helper.assertValueEqual(ReincarnationService.get().store().archive(pending).getList("kept_attachments").orElseThrow().size(),
                        3, "eligible attachment IDs are archived with the transaction");
                keep(); // Simulate a tag reload before recovery.
            });
            service = ReincarnationService.get();
            helper.assertTrue(service.pending(account).isEmpty(), "interrupted attachment restoration recovers");
            helper.assertValueEqual(active.getData(KEPT.get()), 30, "recovery uses the archived whitelist after tags change");
            helper.assertValueEqual(active.getData(TRANSFORMED.get()), 51, "replay starts from the original archive without accumulating custom copies");
            helper.assertValueEqual(COPIES.get(account), 2, "failed attempt and recovery each copy the original exactly once");
            helper.assertTrue(!active.hasData(TAG_ONLY.get()) && !active.hasData(COPY_ONLY.get()) && !active.hasData(VETOED.get()), "recovery applies the same exclusions");

            CHECKS.remove(account);
            keep(KEPT.get());
            active.setData(KEPT.get(), 99);
            ReincarnationRequest base = request(active);
            active = service.reincarnate(active, new ReincarnationRequest(base.dimension(), base.position(), base.yaw(), base.pitch(), base.reason(),
                    new ResetPolicy(false, false, true, true)));
            helper.assertTrue(!active.hasData(KEPT.get()), "resetUnknownModData still overrides automatic retention");
            helper.succeed();
        } finally {
            CHECKS.remove(account);
            COPIES.remove(account);
            tags(originalTags);
            ReincarnationServerConfig.INSTANCE.lifecycle.autoReincarnateOnDeath.setValue(automatic);
            ServerPlayer online = server.getPlayerList().getPlayer(account);
            if (online != null) LifecycleTests.remove(online);
        }
    }

    private static void eventOrder(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ReincarnationService service = ReincarnationService.get();
        ServerPlayer active = LifecycleTests.connect(helper);
        UUID account = active.getUUID();
        List<String> order = new ArrayList<>();
        ORDER.put(account, order);
        try {
            active = service.reincarnate(active, request(active));
            helper.assertValueEqual(order, List.of("archive", "clone", "respawn", "reset"), "reset and save follow other mods' Clone and Respawn listeners");
            CompoundTag stored = server.getPlayerList().loadPlayerData(active.nameAndId()).orElseThrow();
            helper.assertValueEqual(stored.getCompound("NeoForgeData").orElseThrow().getString("test:event_order").orElseThrow(),
                    "archive,clone,respawn,reset", "final player file is written after respawn listeners");

            active = interruptAndRecover(helper, active, order::clear);
            helper.assertValueEqual(order, List.of("login", "reset"), "LOWEST login recovery waits for LOW-priority mod listeners");
            stored = server.getPlayerList().loadPlayerData(active.nameAndId()).orElseThrow();
            helper.assertValueEqual(stored.getCompound("NeoForgeData").orElseThrow().getString("test:event_order").orElseThrow(),
                    "login,reset", "recovery persists after other login listeners");
            helper.succeed();
        } finally {
            ORDER.remove(account);
            ServerPlayer online = server.getPlayerList().getPlayer(account);
            if (online != null) LifecycleTests.remove(online);
        }
    }
}
