package com.iafenvoy.reincarnation.command;

import com.iafenvoy.reincarnation.Reincarnation;
import com.iafenvoy.reincarnation.api.LifeNumbers;
import com.iafenvoy.reincarnation.api.ReincarnationException;
import com.iafenvoy.reincarnation.api.ReincarnationRequest;
import com.iafenvoy.reincarnation.config.ReincarnationServerConfig;
import com.iafenvoy.reincarnation.data.information.LifeViewOpenScreenData;
import com.iafenvoy.reincarnation.lifecycle.ReincarnationService;
import com.iafenvoy.reincarnation.lifecycle.SoulRecord;
import com.iafenvoy.reincarnation.storage.Generations;
import com.iafenvoy.saturn.data.information.PlayerInformation;
import com.iafenvoy.saturn.data.information.PlayerViewSnapshot;
import com.iafenvoy.saturn.data.information.model.InformationDocument;
import com.iafenvoy.saturn.screen.opener.ScreenOpener;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.level.GameType;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

public final class ReincarnationCommand {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private ReincarnationCommand() {
    }

    private static boolean admin(CommandSourceStack source) {
        return Commands.<CommandSourceStack>hasPermission(Commands.LEVEL_GAMEMASTERS).test(source);
    }

    private static RequiredArgumentBuilder<CommandSourceStack, String> account() {
        return Commands.argument("player", StringArgumentType.word()).suggests((context, builder) -> {
            CommandSourceStack source = context.getSource();
            Set<String> names = new TreeSet<>();
            if (admin(source)) {
                source.getServer().getPlayerList().getPlayers().forEach(player -> names.add(player.getGameProfile().name()));
                ReincarnationService.get().store().accounts().forEach(record -> names.add(record.name()));
            } else if (source.getEntity() instanceof ServerPlayer player) names.add(player.getGameProfile().name());
            return SharedSuggestionProvider.suggest(names, builder);
        });
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal(Reincarnation.MOD_ID)
                .executes(context -> status(context.getSource(), context.getSource().getPlayerOrException().getUUID()))
                .then(Commands.literal("status")
                        .executes(context -> status(context.getSource(), context.getSource().getPlayerOrException().getUUID()))
                        .then(account().executes(context -> status(context.getSource(), resolve(context.getSource(), StringArgumentType.getString(context, "player"))))))
                .then(Commands.literal("history")
                        .executes(context -> history(context.getSource(), context.getSource().getPlayerOrException().getUUID(), 1))
                        .then(account().executes(context -> history(context.getSource(), resolve(context.getSource(), StringArgumentType.getString(context, "player")), 1))
                                .then(Commands.argument("page", IntegerArgumentType.integer(1)).executes(context -> history(context.getSource(),
                                        resolve(context.getSource(), StringArgumentType.getString(context, "player")), IntegerArgumentType.getInteger(context, "page"))))))
                .then(Commands.literal("info").then(account().then(Commands.argument("generation", StringArgumentType.word())
                        .executes(context -> info(context.getSource(), resolve(context.getSource(), StringArgumentType.getString(context, "player")),
                                generation(StringArgumentType.getString(context, "generation")))))))
                .then(Commands.literal("view")
                        .executes(context -> view(context.getSource(), context.getSource().getPlayerOrException().getUUID(),
                                Generations.current(context.getSource().getServer(), context.getSource().getPlayerOrException().getUUID()), 0))
                        .then(account().executes(context -> {
                            UUID account = resolve(context.getSource(), StringArgumentType.getString(context, "player"));
                            return view(context.getSource(), account, Generations.current(context.getSource().getServer(), account), 0);
                        }).then(Commands.argument("generation", StringArgumentType.word()).executes(context -> view(context.getSource(),
                                        resolve(context.getSource(), StringArgumentType.getString(context, "player")), generation(StringArgumentType.getString(context, "generation")), 0))
                                .then(Commands.argument("page", IntegerArgumentType.integer(1)).executes(context -> view(context.getSource(),
                                        resolve(context.getSource(), StringArgumentType.getString(context, "player")),
                                        generation(StringArgumentType.getString(context, "generation")), IntegerArgumentType.getInteger(context, "page")))))))
                .then(Commands.literal("rebirth").requires(ReincarnationCommand::admin)
                        .executes(context -> rebirth(context.getSource(), context.getSource().getPlayerOrException(), Component.translatable("reason.reincarnation.command")))
                        .then(Commands.argument("target", EntityArgument.player())
                                .executes(context -> rebirth(context.getSource(), EntityArgument.getPlayer(context, "target"), Component.translatable("reason.reincarnation.command")))
                                .then(Commands.argument("reason", StringArgumentType.greedyString()).executes(context -> rebirth(context.getSource(),
                                        EntityArgument.getPlayer(context, "target"), Component.literal(StringArgumentType.getString(context, "reason"))))))));
    }

    public static UUID resolve(CommandSourceStack source, String value) throws CommandSyntaxException {
        PlayerList players = source.getServer().getPlayerList();
        UUID account;
        try {
            account = UUID.fromString(value);
        } catch (IllegalArgumentException ignored) {
            ServerPlayer online = players.getPlayerByName(value);
            if (online != null) account = online.getUUID();
            else {
                List<SoulRecord> matches = ReincarnationService.get().store().accounts().stream()
                        .filter(record -> record.name().equalsIgnoreCase(value)).toList();
                if (matches.size() != 1) throw error(Component.translatable(matches.isEmpty()
                        ? "command.reincarnation.error.player_not_found" : "command.reincarnation.error.ambiguous_name"));
                account = matches.getFirst().account();
            }
        }
        authorize(source, account);
        return account;
    }

    private static void authorize(CommandSourceStack source, UUID account) throws CommandSyntaxException {
        if (!admin(source) && (!(source.getEntity() instanceof ServerPlayer player) || !player.getUUID().equals(account)))
            throw error(Component.translatable("command.reincarnation.error.permission"));
    }

    private static int status(CommandSourceStack source, UUID account) throws CommandSyntaxException {
        authorize(source, account);
        ReincarnationService service = ReincarnationService.get();
        service.pending(account).ifPresent(tx -> say(source, Component.translatable("command.reincarnation.pending", tx.id().toString(), LifeNumbers.display(tx.from()), LifeNumbers.display(tx.to()))));
        return info(source, account, Generations.current(source.getServer(), account));
    }

    private static int history(CommandSourceStack source, UUID account, int page) throws CommandSyntaxException {
        authorize(source, account);
        ReincarnationService service = ReincarnationService.get();
        List<SoulRecord> records = new ArrayList<>(service.store().history(account));
        service.current(account).filter(record -> record.generation() == Generations.current(source.getServer(), account))
                .ifPresent(records::addFirst);
        if (records.isEmpty()) throw error(Component.translatable("command.reincarnation.error.no_history"));
        int pageSize = ReincarnationServerConfig.INSTANCE.query.historyPageSize.getValue();
        int pages = (records.size() + pageSize - 1) / pageSize;
        if (page > pages) throw error(Component.translatable("command.reincarnation.error.page", pages));
        say(source, Component.translatable("command.reincarnation.history.header", records.getFirst().name(), page, pages, account.toString()));
        for (SoulRecord record : records.subList((page - 1) * pageSize, Math.min(page * pageSize, records.size())))
            say(source, Component.translatable("command.reincarnation.history.entry", LifeNumbers.display(record.generation()),
                    record.endedAt() == 0 ? Component.translatable("command.reincarnation.state.current")
                            : Component.translatable("command.reincarnation.state.ended_at", time(record.endedAt())),
                    record.level(), record.deaths(), reason(record)));
        say(source, Component.translatable("command.reincarnation.history.hint", account.toString()));
        return records.size();
    }

    private static int info(CommandSourceStack source, UUID account, long generation) throws CommandSyntaxException {
        authorize(source, account);
        ReincarnationService service = ReincarnationService.get();
        Optional<SoulRecord> found = service.current(account).filter(record -> record.generation() == generation);
        if (found.isEmpty()) found = service.store().history(account, generation);
        if (found.isEmpty()) {
            if (service.pending(account).isPresent()) {
                say(source, Component.translatable("command.reincarnation.recovering"));
                return 0;
            }
            throw error(Component.translatable("command.reincarnation.error.generation", LifeNumbers.display(generation)));
        }
        SoulRecord record = found.get();
        say(source, Component.translatable("command.reincarnation.info.header", record.name(), LifeNumbers.display(generation),
                Component.translatable(record.endedAt() == 0 ? "command.reincarnation.state.current" : "command.reincarnation.state.ended"), account.toString()));
        say(source, Component.translatable("command.reincarnation.info.times", time(record.startedAt()),
                Component.translatable(record.endedAt() == 0 ? "command.reincarnation.info.updated" : "command.reincarnation.state.ended_at",
                        time(record.endedAt() == 0 ? record.observedAt() : record.endedAt()))));
        say(source, Component.translatable("command.reincarnation.info.reason", reason(record), GameType.byName(record.gameMode()).getLongDisplayName()));
        say(source, Component.translatable("command.reincarnation.info.position", record.dimension(), coordinate(record.position().x), coordinate(record.position().y), coordinate(record.position().z)));
        say(source, Component.translatable("command.reincarnation.info.progress", record.level(), record.totalExperience(), record.inventoryItems(), record.completedAdvancements()));
        say(source, Component.translatable("command.reincarnation.info.stats", record.deaths(), record.playedTicks() / 20));
        if (!record.transactionId().isEmpty())
            say(source, Component.translatable("command.reincarnation.info.transaction", record.transactionId()));
        String command = "/reincarnation view " + account + " " + LifeNumbers.display(generation);
        say(source, Component.translatable("command.reincarnation.view.hint", account.toString(), LifeNumbers.display(generation))
                .withStyle(style -> style.withClickEvent(new ClickEvent.RunCommand(command)))
                .withStyle(ChatFormatting.AQUA, ChatFormatting.UNDERLINE));
        return 1;
    }

    private static int view(CommandSourceStack source, UUID account, long generation, int page) throws CommandSyntaxException {
        authorize(source, account);
        ServerPlayer viewer = source.getPlayerOrException();
        try {
            ReincarnationService service = ReincarnationService.get();
            Optional<PlayerViewSnapshot> found = service.view(account, generation);
            if (found.isEmpty())
                throw error(Component.translatable("command.reincarnation.error.generation", LifeNumbers.display(generation)));
            LifeViewOpenScreenData data = lifeView(service, account, generation, page, PlayerInformation.collectAll(found.get()));
            data.validateSize(viewer.registryAccess());
            ScreenOpener.open(viewer, data);
            return 1;
        } catch (RuntimeException failure) {
            Reincarnation.LOGGER.error("Cannot view life {} of {}", generation, account, failure);
            throw error(Component.translatable("command.reincarnation.view.failed", failure instanceof ReincarnationException known
                    ? known.component() : Component.translatable("error.reincarnation.view_archive")));
        }
    }

    public static LifeViewOpenScreenData lifeView(ReincarnationService service, UUID account, long generation, int requestedPage,
                                                  InformationDocument document) throws CommandSyntaxException {
        List<SoulRecord> records = new ArrayList<>(service.store().history(account));
        service.current(account).filter(record -> record.endedAt() == 0)
                .ifPresent(records::add);
        records.sort(Comparator.comparingLong(SoulRecord::generation).reversed());
        int selected = -1;
        for (int index = 0; index < records.size(); index++)
            if (records.get(index).generation() == generation) {
                selected = index;
                break;
            }
        if (selected < 0)
            throw error(Component.translatable("command.reincarnation.error.generation", LifeNumbers.display(generation)));
        int pages = (records.size() - 1) / LifeViewOpenScreenData.PAGE_SIZE + 1;
        int page = requestedPage == 0 ? selected / LifeViewOpenScreenData.PAGE_SIZE + 1 : requestedPage;
        if (page < 1 || page > pages) throw error(Component.translatable("command.reincarnation.error.page", pages));
        int start = (page - 1) * LifeViewOpenScreenData.PAGE_SIZE;
        List<LifeViewOpenScreenData.Life> lives = records.subList(start, Math.min(start + LifeViewOpenScreenData.PAGE_SIZE, records.size()))
                .stream().map(record -> new LifeViewOpenScreenData.Life(record.generation(), record.endedAt(), reason(record))).toList();
        return new LifeViewOpenScreenData(account, records.get(selected).name(), generation, lives, page, pages,
                records.get(selected).endedAt() == 0, document);
    }

    private static int rebirth(CommandSourceStack source, ServerPlayer target, Component reason) throws CommandSyntaxException {
        String text = reason.getString();
        if (text.isBlank() || text.length() > 256)
            throw error(Component.translatable("command.reincarnation.error.reason"));
        try {
            ServerPlayer fresh = ReincarnationService.get().reincarnate(target, ReincarnationRequest.atSpawn(target, reason));
            Component result = Component.translatable("command.reincarnation.rebirth.success", fresh.getGameProfile().name(), LifeNumbers.display(Generations.current(source.getServer(), fresh.getUUID())));
            // The command source can still hold the removed player after a self-rebirth.
            fresh.sendSystemMessage(result);
            if (source.getEntity() != target) say(source, result);
            return 1;
        } catch (RuntimeException failure) {
            Reincarnation.LOGGER.error("Reincarnation command failed for {}", target.getUUID(), failure);
            Component detail = failure instanceof ReincarnationException known ? known.component()
                    : Component.translatable("error.reincarnation.internal");
            throw error(Component.translatable("command.reincarnation.rebirth.failed", detail));
        }
    }

    private static Component reason(SoulRecord record) {
        if (record.endedAt() == 0) return Component.translatable("command.reincarnation.state.current");
        return record.reason();
    }

    private static long generation(String lifeNumber) throws CommandSyntaxException {
        try {
            return LifeNumbers.generation(lifeNumber);
        } catch (NumberFormatException ignored) {
            throw error(Component.translatable("command.reincarnation.error.life_number", LifeNumbers.display(Long.MAX_VALUE)));
        }
    }

    private static void say(CommandSourceStack source, Component message) {
        source.sendSuccess(() -> message, false);
    }

    private static String coordinate(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private static String time(long epoch) {
        return TIME.format(Instant.ofEpochMilli(epoch));
    }

    private static CommandSyntaxException error(Component message) {
        return new SimpleCommandExceptionType(message).create();
    }
}
