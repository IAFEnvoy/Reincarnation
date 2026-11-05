package com.iafenvoy.reincarnation.gametest;

import com.iafenvoy.reincarnation.api.*;
import com.iafenvoy.reincarnation.command.ReincarnationCommand;
import com.iafenvoy.reincarnation.config.ReincarnationServerConfig;
import com.iafenvoy.reincarnation.data.information.LifeViewOpenScreenData;
import com.iafenvoy.reincarnation.data.information.getter.LifeSourceGetter;
import com.iafenvoy.reincarnation.lifecycle.AdvancementAccess;
import com.iafenvoy.reincarnation.lifecycle.ProgressAccess;
import com.iafenvoy.reincarnation.lifecycle.ReincarnationService;
import com.iafenvoy.reincarnation.lifecycle.SoulRecord;
import com.iafenvoy.reincarnation.lifespan.LifespanService;
import com.iafenvoy.reincarnation.network.ServerNetworkHandler;
import com.iafenvoy.reincarnation.network.payload.LifeViewRefreshC2SPayload;
import com.iafenvoy.reincarnation.network.payload.LifeViewRefreshS2CPayload;
import com.iafenvoy.reincarnation.view.ReincarnationViews;
import com.iafenvoy.saturn.data.information.PlayerInformation;
import com.iafenvoy.saturn.data.information.PlayerViewSnapshot;
import com.iafenvoy.saturn.data.information.getter.EntityValueGetters;
import com.iafenvoy.saturn.data.information.model.*;
import com.iafenvoy.saturn.network.payload.OpenScreenS2CPayload;
import com.iafenvoy.saturn.screen.opener.OpenScreenData;
import com.iafenvoy.saturn.util.FileAccess;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.EncoderException;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.registration.ChannelAttributes;
import net.neoforged.neoforge.network.registration.NetworkChannel;
import net.neoforged.neoforge.network.registration.NetworkPayloadSetup;

import java.util.*;
import java.util.function.Consumer;

final class PlayerViewTests {
    static Map<String, Consumer<GameTestHelper>> tests() {
        return Map.of("player_views_archive", PlayerViewTests::archive,
                "player_views_legacy", PlayerViewTests::legacy,
                "player_views_summary", PlayerViewTests::summary,
                "player_views_browser", PlayerViewTests::browser,
                "player_views_screen_transport", PlayerViewTests::screenTransport,
                "player_views_refresh", PlayerViewTests::refresh);
    }

    private static void archive(GameTestHelper helper) {
        ReincarnationService service = ReincarnationService.get();
        ServerPlayer active = LifecycleTests.connect(helper);
        try {
            active.setHealth(8);
            active.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 3));
            UUID account = active.getUUID();
            active = service.reincarnate(active, new ReincarnationRequest(active.level().dimension(), active.position(), 0, 0, Component.literal("view test"), ResetPolicy.DEFAULT));
            SoulRecord record = service.store().history(account, 0).orElseThrow();
            CompoundTag saved = service.store().archive(record).orElseThrow();
            helper.assertTrue(saved.contains("player_view"), "new archives contain a detached player view");
            String hash = service.store().archiveHash(UUID.fromString(record.transactionId()));
            PlayerViewSnapshot past = service.view(account, 0).orElseThrow();
            helper.assertValueEqual(past.read(EntityValueGetters.HEALTH).value().orElseThrow(), 8.0, "historical health survives rebirth");
            helper.assertValueEqual(past.read(EntityValueGetters.INVENTORY).value().orElseThrow().get("0").count(), 3, "historical inventory survives reset");
            helper.assertValueEqual(past.read(ReincarnationViews.LIFE).value().orElseThrow().generation(), 0L, "life identity captured before generation advances");
            helper.assertTrue(past.read(EntityValueGetters.STATISTICS).value().isPresent(), "host supplies statistics absent from Saturn's live parser");
            PlayerViewSnapshot current = service.view(account, 1).orElseThrow();
            helper.assertTrue(!past.meta().ref().equals(current.meta().ref()), "same account has separate references for separate lives");
            helper.assertTrue(current.read(EntityValueGetters.INVENTORY).value().orElseThrow().isEmpty(), "current life uses fresh inventory");
            active.setHealth(5);
            helper.assertValueEqual(past.read(EntityValueGetters.HEALTH).value().orElseThrow(), 8.0, "later changes cannot alter historical values");
            helper.assertValueEqual(service.store().archiveHash(UUID.fromString(record.transactionId())), hash, "viewing does not rewrite the archive");

            InformationDocument document = PlayerInformation.collectAll(past);
            helper.assertTrue(!document.rows(ReincarnationViews.group(helper.getLevel().registryAccess())).isEmpty(), "collectAll includes lifecycle extension rows");
            helper.assertValueEqual(document.rows(ReincarnationViews.group(helper.getLevel().registryAccess())).size(), 14, "all archived life rows load from JSON");
            helper.assertTrue(document.rows().stream().noneMatch(row -> row.id().getNamespace().equals("reincarnation") && row.id().getPath().equals("summary_position")),
                    "complete archives do not display summary-only coordinates");
            InformationEntry coverage = document.rows().stream().filter(row -> row.id().getNamespace().equals("reincarnation") && row.id().getPath().equals("source")).findFirst().orElseThrow();
            helper.assertValueEqual(((TranslatableContents) ((InformationValue.Text) coverage.value()).text().getContents()).getKey(),
                    "info.reincarnation.source.archive", "archived data coverage uses the host's source kind");

            CompoundTag altered = saved.copy();
            altered.putString("tampered", "test");
            try {
                helper.assertTrue(FileAccess.NBT.save(CompoundTag.CODEC, service.store().archiveFile(UUID.fromString(record.transactionId())), altered), "tampered archive written");
                try {
                    service.view(account, 0);
                    helper.fail("checksum mismatch was accepted");
                } catch (IllegalStateException expected) {
                    helper.assertTrue(expected.getMessage().contains("checksum"), "tampering is rejected before parsing");
                }
            } finally {
                helper.assertTrue(FileAccess.NBT.save(CompoundTag.CODEC, service.store().archiveFile(UUID.fromString(record.transactionId())), saved), "archive fixture restored");
            }
            helper.succeed();
        } finally {
            LifecycleTests.remove(active);
        }
    }

    private static void legacy(GameTestHelper helper) {
        ServerPlayer player = LifecycleTests.connect(helper);
        try {
            player.setHealth(9);
            player.getInventory().setItem(0, new ItemStack(Items.EMERALD, 4));
            SoulRecord record = SoulRecord.capture(player, 0, 1, System.currentTimeMillis(), Component.literal("legacy"), UUID.randomUUID().toString());
            CompoundTag saved = new CompoundTag();
            saved.put("player", ReincarnationService.playerData(player));
            saved.putString("stats", ((ProgressAccess) player.getStats()).reincarnation$json());
            saved.putString("advancements", ((AdvancementAccess) player.getAdvancements()).reincarnation$json());
            PlayerViewSnapshot view = ReincarnationViews.archive(player.registryAccess(), record, saved);
            helper.assertValueEqual(view.read(EntityValueGetters.HEALTH).value().orElseThrow(), 9.0, "old archives parse saved health without constructing a player");
            helper.assertValueEqual(view.read(EntityValueGetters.INVENTORY).value().orElseThrow().get("0").count(), 4, "old archives parse saved inventory");
            helper.assertTrue(view.read(EntityValueGetters.MAX_HEALTH).value().isEmpty(), "historical effective maximum is not guessed");
            helper.assertValueEqual(view.read(EntityValueGetters.PROFILE).value().orElseThrow().name(), record.name(), "host supplements name missing from vanilla player NBT");

            PlayerViewSnapshot recorded = ReincarnationViews.capture(player, record, "{}", "{}");
            saved.put("player_view", PlayerViewSnapshot.CODEC.encodeStart(player.registryAccess().createSerializationContext(NbtOps.INSTANCE), recorded).getOrThrow());
            saved.getCompound("player").orElseThrow().putString("Health", "broken");
            helper.assertValueEqual(ReincarnationViews.archive(player.registryAccess(), record, saved).read(EntityValueGetters.HEALTH).value().orElseThrow(), 9.0,
                    "recorded fields take precedence over raw NBT fallback");
            saved.remove("player_view");
            PlayerViewSnapshot damaged = ReincarnationViews.archive(player.registryAccess(), record, saved);
            helper.assertValueEqual(damaged.read(EntityValueGetters.HEALTH).missingReason().orElseThrow(), MissingReason.INVALID, "invalid legacy field is explicit");
            helper.assertTrue(damaged.read(EntityValueGetters.INVENTORY).value().isPresent(), "one broken field does not hide other saved data");
            helper.succeed();
        } finally {
            LifecycleTests.remove(player);
        }
    }

    private static void summary(GameTestHelper helper) {
        SoulRecord record = new SoulRecord(UUID.randomUUID(), "Summary", 0, 1, 2, 2, Component.literal("summary"), "minecraft:overworld",
                Vec3.ZERO, "survival", 3, 20, 4, 5, 6, 200, "");
        PlayerViewSnapshot view = ReincarnationViews.summary(helper.getLevel().registryAccess(), record);
        helper.assertValueEqual(view.meta().sourceKind(), PlayerViewMeta.SourceKind.SUMMARY, "summary remains explicitly incomplete");
        helper.assertTrue(view.read(EntityValueGetters.HEALTH).value().isEmpty(), "summary does not invent health or inventory");
        helper.assertTrue(view.read(EntityValueGetters.INVENTORY).value().isEmpty(), "an item count does not imply a full inventory");
        InformationDocument document = PlayerInformation.collect(view, ReincarnationViews.group(helper.getLevel().registryAccess()));
        helper.assertTrue(document.rows().stream().allMatch(row -> row.groups().contains(ReincarnationViews.group(helper.getLevel().registryAccess()))), "group filtering remains available");
        InformationValue.Text number = (InformationValue.Text) document.rows().stream().filter(row -> row.id().getPath().equals("generation")).findFirst().orElseThrow().value();
        helper.assertValueEqual(number.text().getString(), "1", "stored generation zero displays as life one");
        helper.assertValueEqual(document.rows().size(), 15, "all summary rows load from the life tag");
        helper.assertTrue(document.rows().stream().anyMatch(row -> row.id().getPath().equals("summary_position")), "summary-only coordinates are visible");
        PlayerViewSnapshot.Builder oldBuilder = PlayerViewSnapshot.builder(view.meta(), helper.getLevel().registryAccess().createSerializationContext(NbtOps.INSTANCE));
        oldBuilder.put(ReincarnationViews.LIFE, record, ValueOrigin.SAVED);
        PlayerViewSnapshot oldSnapshot = oldBuilder.build();
        helper.assertValueEqual(oldSnapshot.read(LifeSourceGetter.INSTANCE).value().orElseThrow(), PlayerViewMeta.SourceKind.SUMMARY,
                "old snapshots derive missing coverage from their own metadata");
        helper.assertValueEqual(PlayerInformation.collect(oldSnapshot, ReincarnationViews.group(helper.getLevel().registryAccess())).rows(), document.rows(),
                "old life snapshots use the same JSON rows without being rewritten");
        CompoundTag maximum = record.save();
        maximum.putLong("generation", Long.MAX_VALUE);
        InformationDocument lastLife = PlayerInformation.collect(ReincarnationViews.summary(helper.getLevel().registryAccess(), SoulRecord.load(maximum)),
                ReincarnationViews.group(helper.getLevel().registryAccess()));
        InformationValue.Text lastNumber = (InformationValue.Text) lastLife.rows().stream().filter(row -> row.id().getPath().equals("generation")).findFirst().orElseThrow().value();
        helper.assertValueEqual(lastNumber.text().getString(), LifeNumbers.display(Long.MAX_VALUE), "large generations preserve exact long precision");
        helper.succeed();
    }

    private static void browser(GameTestHelper helper) {
        ReincarnationService service = ReincarnationService.get();
        UUID account = UUID.randomUUID();
        for (int generation = 0; generation < 37; generation++)
            service.store().history(browserRecord(account, generation, 2));
        service.store().current(browserRecord(account, 37, 0));
        InformationDocument document = PlayerInformation.collectAll(ReincarnationViews.summary(helper.getLevel().registryAccess(), browserRecord(account, 0, 2)));
        try {
            LifeViewOpenScreenData oldest = ReincarnationCommand.lifeView(service, account, 0, 0, document);
            helper.assertValueEqual(oldest.page(), 2, "opening a life locates its history page automatically");
            helper.assertValueEqual(oldest.pages(), 2, "current life participates in pagination");
            helper.assertValueEqual(oldest.lives().size(), 6, "last page contains only its remaining summaries");
            helper.assertValueEqual(oldest.lives().getFirst().generation(), 5L, "summaries sort from newest to oldest");
            helper.assertValueEqual(oldest.lives().getLast().generation(), 0L, "first life remains accessible");
            helper.assertTrue(!oldest.current(), "historical details do not poll for updates");
            LifeViewOpenScreenData newest = ReincarnationCommand.lifeView(service, account, 37, 0, document);
            helper.assertValueEqual(newest.page(), 1, "current life starts on the first page");
            helper.assertValueEqual(newest.lives().size(), LifeViewOpenScreenData.PAGE_SIZE, "page size is bounded independently of chat history configuration");
            helper.assertValueEqual(newest.lives().getFirst().endedAt(), 0L, "current life is marked as active");
            helper.assertTrue(newest.current(), "current life enables periodic updates");
            helper.assertTrue(ReincarnationCommand.lifeView(service, account, 37, 2, document).current(),
                    "current details keep refreshing even when the current life is absent from the list page");
            LifeViewOpenScreenData page = ReincarnationCommand.lifeView(service, account, 0, 1, document);
            helper.assertValueEqual(page.generation(), 0L, "browsing another page preserves the selected detail");
            helper.assertValueEqual(page.lives().getFirst().generation(), 37L, "explicit page controls the list independently");
            helper.assertValueEqual(page.document(), document, "pagination preserves the selected life document");
            try {
                ReincarnationCommand.lifeView(service, account, 0, 3, document);
                helper.fail("out-of-range life page was accepted");
            } catch (CommandSyntaxException expected) {
                helper.assertValueEqual(((TranslatableContents) ((Component) expected.getRawMessage()).getContents()).getKey(),
                        "command.reincarnation.error.page", "invalid pages report a localized error");
            }
            helper.assertValueEqual(service.store().history(account).size(), 37, "browsing does not alter history");
            helper.succeed();
        } catch (CommandSyntaxException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static SoulRecord browserRecord(UUID account, long generation, long endedAt) {
        return new SoulRecord(account, "Browser", generation, 1, endedAt, 2, Component.literal("summary"), "minecraft:overworld",
                Vec3.ZERO, "survival", 0, 0, 0, 0, 0, 0, "");
    }

    @SuppressWarnings("UnstableApiUsage")
    private static void screenTransport(GameTestHelper helper) {
        ServerPlayer player = LifecycleTests.connect(helper);
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), player.registryAccess());
        try {
            // Embedded test players skip the login negotiation performed by a real modded client.
            Identifier payloadId = OpenScreenS2CPayload.TYPE.id();
            ChannelAttributes.setPayloadSetup(player.connection.getConnection(), new NetworkPayloadSetup(Map.of(
                    ConnectionProtocol.PLAY, Map.of(payloadId, new NetworkChannel(payloadId, "1")))));
            CommandSourceStack source = player.createCommandSourceStack().withPermission(PermissionSet.NO_PERMISSIONS);
            CommandDispatcher<CommandSourceStack> dispatcher = helper.getLevel().getServer().getCommands().getDispatcher();
            try {
                dispatcher.execute("reincarnation view " + UUID.randomUUID() + " 1 1", source);
                helper.fail("a normal player opened another account's life browser");
            } catch (CommandSyntaxException expected) {
                helper.assertValueEqual(((TranslatableContents) ((Component) expected.getRawMessage()).getContents()).getKey(),
                        "command.reincarnation.error.permission", "GUI navigation applies the same ownership check as text queries");
            }
            EmbeddedChannel channel = (EmbeddedChannel) player.connection.getConnection().channel();
            while (channel.readOutbound() != null) {
            }
            helper.assertValueEqual(dispatcher.execute("reincarnation view", source), 1, "normal players can open their own browser");
            LifeViewOpenScreenData opened = null;
            Object packet;
            while ((packet = channel.readOutbound()) != null)
                if (packet instanceof ClientboundCustomPayloadPacket(
                        CustomPacketPayload payload
                ) && payload instanceof OpenScreenS2CPayload(
                        OpenScreenData data1
                )
                        && data1 instanceof LifeViewOpenScreenData data) opened = data;
            helper.assertTrue(opened != null, "the command sends the registered Saturn open-screen payload");
            helper.assertValueEqual(opened.account(), player.getUUID(), "screen payload belongs to the authorized account");
            InformationEntry template = opened.document().rows().getFirst();
            Component colored = Component.literal("Styled value").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD);
            InformationEntry styled = new InformationEntry(template.row(), template.groups(), template.label(),
                    new InformationValue.Values(List.of(new InformationValue.Text(colored), new InformationValue.Number(42))),
                    Optional.of(Component.literal("Tooltip").withStyle(ChatFormatting.AQUA, ChatFormatting.ITALIC)));
            LifeViewOpenScreenData data = new LifeViewOpenScreenData(player.getUUID(), player.getGameProfile().name(), Long.MAX_VALUE,
                    List.of(new LifeViewOpenScreenData.Life(Long.MAX_VALUE, 1234, Component.translatable("reason.reincarnation.command").withStyle(ChatFormatting.YELLOW))),
                    1, 1, false, new InformationDocument(List.of(styled)));
            data.validateSize(player.registryAccess());
            OpenScreenS2CPayload.STREAM_CODEC.encode(buffer, new OpenScreenS2CPayload(data));
            LifeViewOpenScreenData decoded = (LifeViewOpenScreenData) OpenScreenS2CPayload.STREAM_CODEC.decode(buffer).data();
            helper.assertValueEqual(decoded, data, "Saturn dispatch preserves registry groups, styles, nested values and full generation precision");
            helper.assertTrue(!buffer.isReadable(), "screen decoding consumes the complete payload");

            InformationEntry large = new InformationEntry(template.row(), template.groups(), template.label(),
                    new InformationValue.Text(Component.literal("x".repeat(16_000))), Optional.empty());
            LifeViewOpenScreenData oversized = new LifeViewOpenScreenData(data.account(), data.name(), 0, data.lives(), 1, 1, true,
                    new InformationDocument(Collections.nCopies(70, large)));
            try {
                oversized.validateSize(player.registryAccess());
                helper.fail("oversized screen data passed the command preflight");
            } catch (ReincarnationException expected) {
                helper.assertValueEqual(((TranslatableContents) expected.component().getContents()).getKey(),
                        "error.reincarnation.view_too_large", "oversized documents have a localized failure");
            }
            buffer.clear();
            try {
                LifeViewOpenScreenData.STREAM_CODEC.encode(buffer, oversized);
                helper.fail("oversized screen data was encoded");
            } catch (EncoderException _) {
            }
            buffer.clear();
            buffer.writeVarInt(LifeViewOpenScreenData.MAX_BYTES + 1);
            try {
                LifeViewOpenScreenData.STREAM_CODEC.decode(buffer);
                helper.fail("oversized screen data was decoded");
            } catch (DecoderException _) {
            }
            helper.succeed();
        } catch (CommandSyntaxException failure) {
            throw new IllegalStateException(failure);
        } finally {
            buffer.release();
            LifecycleTests.remove(player);
        }
    }

    @SuppressWarnings("UnstableApiUsage")
    private static void refresh(GameTestHelper helper) {
        ServerPlayer player = LifecycleTests.connect(helper);
        ServerPlayer target = LifecycleTests.connect(helper);
        MinecraftServer server = helper.getLevel().getServer();
        ReincarnationService service = ReincarnationService.get();
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), player.registryAccess());
        try {
            Identifier payloadId = LifeViewRefreshS2CPayload.TYPE.id();
            ChannelAttributes.setPayloadSetup(player.connection.getConnection(), new NetworkPayloadSetup(Map.of(
                    ConnectionProtocol.PLAY, Map.of(payloadId, new NetworkChannel(payloadId, "1")))));
            EmbeddedChannel channel = (EmbeddedChannel) player.connection.getConnection().channel();
            SoulRecord before = service.current(player.getUUID()).orElseThrow();
            player.setHealth(7);
            LifespanApi.set(player, 240000);
            LifespanApi.add(player, -24000);
            LifeViewRefreshC2SPayload request = new LifeViewRefreshC2SPayload(UUID.randomUUID(), player.getUUID(), 0);
            LifeViewRefreshC2SPayload.STREAM_CODEC.encode(buffer, request);
            helper.assertValueEqual(LifeViewRefreshC2SPayload.STREAM_CODEC.decode(buffer), request, "refresh requests preserve their identity and target");
            ServerNetworkHandler.refresh(player, request);
            LifeViewRefreshS2CPayload first = receiveRefresh(helper, channel);
            helper.assertValueEqual(first.requestId(), request.requestId(), "refresh replies identify the request for rejecting late responses");
            helper.assertTrue(first.current(), "online current lives continue refreshing");
            helper.assertValueEqual(health(first), 7.0, "refresh collects current health");
            helper.assertValueEqual(((InformationValue.Text) first.document().orElseThrow().rows().stream()
                            .filter(row -> row.id().toString().equals("reincarnation:lifespan")).findFirst().orElseThrow().value()).text(),
                    LifespanService.display(216000, 240000, ReincarnationServerConfig.INSTANCE.lifespan.ticksPerYear.getValue()),
                    "refresh includes the live age and total lifespan");
            buffer.clear();
            LifeViewRefreshS2CPayload.STREAM_CODEC.encode(buffer, first);
            helper.assertValueEqual(LifeViewRefreshS2CPayload.STREAM_CODEC.decode(buffer), first, "refresh documents round-trip through registry-aware codecs");
            helper.assertTrue(!buffer.isReadable(), "refresh decoding consumes the complete response");
            player.setHealth(13);
            ServerNetworkHandler.refresh(player, new LifeViewRefreshC2SPayload(UUID.randomUUID(), player.getUUID(), 0));
            helper.assertValueEqual(health(receiveRefresh(helper, channel)), 13.0, "subsequent refreshes recollect changed player data");
            helper.assertValueEqual(service.store().current(player.getUUID()).orElseThrow(), before, "background refresh does not write the current-life summary");

            ServerNetworkHandler.refresh(player, new LifeViewRefreshC2SPayload(UUID.randomUUID(), target.getUUID(), 0));
            LifeViewRefreshS2CPayload denied = receiveRefresh(helper, channel);
            helper.assertTrue(!denied.current() && denied.document().isEmpty(), "ordinary players cannot refresh another account");
            server.getPlayerList().op(new NameAndId(player.getGameProfile()), Optional.of(LevelBasedPermissionSet.GAMEMASTER), Optional.empty());
            target.setHealth(11);
            ServerNetworkHandler.refresh(player, new LifeViewRefreshC2SPayload(UUID.randomUUID(), target.getUUID(), 0));
            helper.assertValueEqual(health(receiveRefresh(helper, channel)), 11.0, "administrators can refresh another online player");
            server.getPlayerList().deop(new NameAndId(player.getGameProfile()));
            ServerNetworkHandler.refresh(player, new LifeViewRefreshC2SPayload(UUID.randomUUID(), target.getUUID(), 0));
            helper.assertTrue(receiveRefresh(helper, channel).document().isEmpty(), "each refresh rechecks administrator permissions");

            player = service.reincarnate(player, ReincarnationRequest.atSpawn(player, Component.literal("refresh test")));
            ServerNetworkHandler.refresh(player, request);
            LifeViewRefreshS2CPayload ended = receiveRefresh(helper, channel);
            helper.assertTrue(!ended.current(), "a life that ended while open stops refreshing");
            helper.assertValueEqual(health(ended), 13.0, "the final update comes from the archived life rather than the new player");
            server.getPlayerList().op(new NameAndId(player.getGameProfile()), Optional.of(LevelBasedPermissionSet.GAMEMASTER), Optional.empty());
            LifecycleTests.remove(target);
            ServerNetworkHandler.refresh(player, new LifeViewRefreshC2SPayload(UUID.randomUUID(), target.getUUID(), 0));
            LifeViewRefreshS2CPayload offline = receiveRefresh(helper, channel);
            helper.assertTrue(!offline.current() && offline.document().isEmpty(), "offline targets stop background polling without loading summaries");

            InformationEntry template = first.document().orElseThrow().rows().getFirst();
            InformationEntry large = new InformationEntry(template.row(), template.groups(), template.label(),
                    new InformationValue.Text(Component.literal("x".repeat(16000))), Optional.empty());
            buffer.clear();
            try {
                LifeViewRefreshS2CPayload.STREAM_CODEC.encode(buffer, new LifeViewRefreshS2CPayload(request.requestId(), request.account(), 0, first.life(),
                        Optional.of(new InformationDocument(Collections.nCopies(70, large)))));
                helper.fail("oversized refresh data was encoded");
            } catch (EncoderException _) {
            }
            buffer.clear();
            buffer.writeVarInt(LifeViewOpenScreenData.MAX_BYTES + 1);
            try {
                LifeViewRefreshS2CPayload.STREAM_CODEC.decode(buffer);
                helper.fail("oversized refresh data was decoded");
            } catch (DecoderException _) {
            }
            helper.succeed();
        } finally {
            buffer.release();
            server.getPlayerList().deop(new NameAndId(player.getGameProfile()));
            LifecycleTests.remove(player);
            if (server.getPlayerList().getPlayer(target.getUUID()) != null) LifecycleTests.remove(target);
        }
    }

    private static LifeViewRefreshS2CPayload receiveRefresh(GameTestHelper helper, EmbeddedChannel channel) {
        Object packet;
        while ((packet = channel.readOutbound()) != null)
            if (packet instanceof ClientboundCustomPayloadPacket(
                    CustomPacketPayload payload
            ) && payload instanceof LifeViewRefreshS2CPayload response)
                return response;
        helper.fail("the registered refresh payload was not sent");
        throw new AssertionError();
    }

    private static double health(LifeViewRefreshS2CPayload response) {
        return ((InformationValue.Meter) response.document().orElseThrow().rows().stream()
                .filter(row -> row.id().toString().equals("saturn:health")).findFirst().orElseThrow().value()).value();
    }
}
