package com.iafenvoy.reincarnation.gametest;

import com.iafenvoy.reincarnation.api.ReincarnationRequest;
import com.iafenvoy.reincarnation.api.ResetPolicy;
import com.iafenvoy.reincarnation.command.ReincarnationCommand;
import com.iafenvoy.reincarnation.data.information.LifeViewOpenScreenData;
import com.iafenvoy.reincarnation.lifecycle.LifeTransaction;
import com.iafenvoy.reincarnation.lifecycle.ReincarnationService;
import com.iafenvoy.reincarnation.lifecycle.SoulRecord;
import com.iafenvoy.reincarnation.view.ReincarnationViews;
import com.iafenvoy.saturn.data.information.PlayerInformation;
import com.iafenvoy.saturn.data.information.PlayerViewSnapshot;
import com.iafenvoy.saturn.data.information.model.InformationDocument;
import com.iafenvoy.saturn.data.information.model.InformationEntry;
import com.iafenvoy.saturn.data.information.model.InformationValue;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.serialization.JsonOps;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

final class ReasonTests {
    static Map<String, Consumer<GameTestHelper>> tests() {
        return Map.of("reason_components", ReasonTests::components, "reason_commands", ReasonTests::commands);
    }

    private static Component styledReason() {
        return Component.translatableWithFallback("test.reincarnation.reason", "Reborn: %s", Component.literal("argument").withStyle(ChatFormatting.AQUA))
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
                .append(Component.literal(" suffix").withStyle(ChatFormatting.ITALIC))
                .withStyle(style -> style.withClickEvent(new ClickEvent.SuggestCommand("/reincarnation history"))
                        .withHoverEvent(new HoverEvent.ShowText(Component.literal("Details"))));
    }

    private static void components(GameTestHelper helper) {
        List<Component> reasons = List.of(Component.literal("reason.reincarnation.death"),
                Component.translatable("reason.reincarnation.death"), styledReason());
        for (Component reason : reasons) {
            ReincarnationRequest request = new ReincarnationRequest(helper.getLevel().dimension(), Vec3.ZERO, 0, 0, reason, ResetPolicy.DEFAULT);
            CompoundTag encoded = (CompoundTag) ReincarnationRequest.CODEC.encodeStart(NbtOps.INSTANCE, request).getOrThrow();
            helper.assertTrue(!encoded.contains("reason_translation_key"), "new requests contain no separate translation key");
            helper.assertValueEqual(ReincarnationRequest.CODEC.parse(NbtOps.INSTANCE, encoded).getOrThrow().reason(), reason,
                    "request NBT preserves the complete component");
            helper.assertValueEqual(ReincarnationRequest.CODEC.parse(JsonOps.INSTANCE,
                            ReincarnationRequest.CODEC.encodeStart(JsonOps.INSTANCE, request).getOrThrow()).getOrThrow().reason(), reason,
                    "request JSON preserves the complete component");

            SoulRecord record = new SoulRecord(UUID.randomUUID(), "Reason", 0, 1, 2, 2, reason, "minecraft:overworld",
                    Vec3.ZERO, "survival", 0, 0, 0, 0, 0, 0, UUID.randomUUID().toString());
            CompoundTag summary = record.save();
            helper.assertTrue(!summary.contains("reason_key"), "new summaries contain no separate translation key");
            helper.assertValueEqual(SoulRecord.load(summary).reason(), reason, "summary NBT preserves the complete component");
            helper.assertValueEqual(SoulRecord.CODEC.parse(JsonOps.INSTANCE,
                            SoulRecord.CODEC.encodeStart(JsonOps.INSTANCE, record).getOrThrow()).getOrThrow().reason(), reason,
                    "summary JSON preserves the complete component");

            LifeTransaction tx = new LifeTransaction(UUID.randomUUID(), record.account(), record.name(), 0, 1, 1,
                    request, "unused", LifeTransaction.State.COMMITTING);
            CompoundTag transaction = (CompoundTag) LifeTransaction.CODEC.encodeStart(NbtOps.INSTANCE, tx).getOrThrow();
            transaction.getCompound("request").orElseThrow().putString("reason", "Death");
            transaction.getCompound("request").orElseThrow().putString("reason_translation_key", "reason.reincarnation.death");
            LifeTransaction migrated = LifeTransaction.CODEC.parse(NbtOps.INSTANCE, transaction).getOrThrow();
            Component legacyReason = Component.translatableWithFallback("reason.reincarnation.death", "Death");
            helper.assertValueEqual(migrated.request().reason(), Component.literal("Death"), "requests ignore obsolete translation fields and decode literal reasons");
            helper.assertTrue(!((CompoundTag) LifeTransaction.CODEC.encodeStart(NbtOps.INSTANCE, migrated).getOrThrow())
                    .getCompound("request").orElseThrow().contains("reason_translation_key"), "legacy requests are saved in the new format");

            encoded.putString("reason", "reason.reincarnation.death");
            helper.assertValueEqual(ReincarnationRequest.CODEC.parse(NbtOps.INSTANCE, encoded).getOrThrow().reason(),
                    Component.literal("reason.reincarnation.death"), "old literal reasons are never guessed to be translation keys");
            summary.putString("reason", "Death");
            summary.putString("reason_key", "reason.reincarnation.death");
            SoulRecord migratedRecord = SoulRecord.load(summary);
            helper.assertValueEqual(migratedRecord.reason(), legacyReason, "legacy summaries retain their translation and fallback");
            helper.assertTrue(!migratedRecord.save().contains("reason_key"), "legacy summaries are saved in the new format");
            summary.remove("reason_key");
            helper.assertValueEqual(SoulRecord.load(summary).reason(), Component.literal("Death"), "old summaries without a key remain literal");
        }
        helper.succeed();
    }

    private static void commands(GameTestHelper helper) {
        ServerPlayer active = LifecycleTests.connect(helper);
        try {
            MinecraftServer server = helper.getLevel().getServer();
            CommandDispatcher<CommandSourceStack> dispatcher = server.getCommands().getDispatcher();
            ReincarnationService service = ReincarnationService.get();
            UUID account = active.getUUID();
            dispatcher.execute("reincarnation rebirth", active.createCommandSourceStack().withPermission(PermissionSet.ALL_PERMISSIONS));
            active = active.connection.player;
            helper.assertValueEqual(service.store().history(account, 0).orElseThrow().reason(), Component.translatable("reason.reincarnation.command"),
                    "the default command archives a translatable component");
            dispatcher.execute("reincarnation rebirth " + active.getGameProfile().name() + " reason.reincarnation.death",
                    active.createCommandSourceStack().withPermission(PermissionSet.ALL_PERMISSIONS));
            active = active.connection.player;
            helper.assertValueEqual(service.store().history(account, 1).orElseThrow().reason(), Component.literal("reason.reincarnation.death"),
                    "custom command reasons stay literal even if they match a translation key");

            Component reason = styledReason();
            active = service.reincarnate(active, ReincarnationRequest.atSpawn(active, reason));
            helper.assertValueEqual(service.store().history(account, 2).orElseThrow().reason(), reason, "disk history retains component arguments and style");
            PlayerViewSnapshot view = service.view(account, 2).orElseThrow();
            helper.assertValueEqual(view.read(ReincarnationViews.LIFE).value().orElseThrow().reason(), reason, "the archived Saturn getter retains the component");
            InformationDocument document = PlayerInformation.collectAll(view);
            InformationEntry row = document.rows().stream().filter(entry -> entry.id().toString().equals("reincarnation:reason")).findFirst().orElseThrow();
            helper.assertValueEqual(((InformationValue.Text) row.value()).text(), reason, "the information row displays the complete component");
            LifeViewOpenScreenData browser = ReincarnationCommand.lifeView(service, account, 2, 0, document);
            helper.assertValueEqual(browser.lives().stream().filter(life -> life.generation() == 2).findFirst().orElseThrow().reason(), reason,
                    "the life browser displays the complete component");
            EmbeddedChannel channel = (EmbeddedChannel) active.connection.getConnection().channel();
            while (channel.readOutbound() != null) {
            }
            dispatcher.execute("reincarnation info " + account + " 3", active.createCommandSourceStack());
            boolean found = false;
            Object packet;
            while ((packet = channel.readOutbound()) != null)
                if (packet instanceof ClientboundSystemChatPacket chat && chat.content().getContents() instanceof TranslatableContents contents
                        && contents.getKey().equals("command.reincarnation.info.reason")) {
                    helper.assertValueEqual(contents.getArgs()[0], reason, "the chat query displays the complete component");
                    found = true;
                }
            helper.assertTrue(found, "the info command emits a reason message");
            try {
                dispatcher.execute("reincarnation rebirth " + active.getGameProfile().name() + " " + "x".repeat(257),
                        active.createCommandSourceStack().withPermission(PermissionSet.ALL_PERMISSIONS));
                helper.fail("overlong command reason accepted");
            } catch (CommandSyntaxException expected) {
                helper.assertValueEqual(((TranslatableContents) ((Component) expected.getRawMessage()).getContents()).getKey(),
                        "command.reincarnation.error.reason", "command validation still reports the localized reason error");
            }
            helper.succeed();
        } catch (CommandSyntaxException failure) {
            throw new IllegalStateException(failure);
        } finally {
            ServerPlayer online = helper.getLevel().getServer().getPlayerList().getPlayer(active.getUUID());
            if (online != null) LifecycleTests.remove(online);
        }
    }
}
