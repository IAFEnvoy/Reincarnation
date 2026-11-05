package com.iafenvoy.reincarnation.command;

import com.iafenvoy.reincarnation.api.LifespanApi;
import com.iafenvoy.reincarnation.api.RebirthMode;
import com.iafenvoy.reincarnation.config.ReincarnationServerConfig;
import com.iafenvoy.reincarnation.lifespan.Lifespan;
import com.iafenvoy.reincarnation.lifespan.LifespanService;
import com.iafenvoy.saturn.runtime.resulting.Result;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import java.util.Collection;
import java.util.List;

import static net.minecraft.commands.Commands.*;

public final class LifespanCommand {
    private LifespanCommand() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        return literal("lifespan").requires(hasPermission(LEVEL_GAMEMASTERS))
                .executes(ctx -> get(ctx, false))
                .then(literal("get").executes(ctx -> get(ctx, false))
                        .then(argument("targets", EntityArgument.entities()).executes(ctx -> get(ctx, true))))
                .then(literal("set").then(argument("targets", EntityArgument.entities())
                        .then(argument("ticks", LongArgumentType.longArg(0)).executes(ctx -> write(ctx, true)))))
                .then(literal("add").then(argument("targets", EntityArgument.entities())
                        .then(argument("ticks", LongArgumentType.longArg()).executes(ctx -> write(ctx, false)))))
                .then(literal("unlimited").then(argument("targets", EntityArgument.entities()).executes(ctx -> act(ctx, false))))
                .then(literal("reincarnate").then(argument("targets", EntityArgument.entities()).executes(ctx -> act(ctx, true))));
    }

    private static int get(CommandContext<CommandSourceStack> context, boolean targets) throws CommandSyntaxException {
        Collection<? extends Entity> entities = targets ? EntityArgument.getEntities(context, "targets") : List.of(context.getSource().getPlayerOrException());
        for (Entity entity : entities) {
            Lifespan value = LifespanApi.get(entity);
            context.getSource().sendSuccess(() -> Component.translatable("command.reincarnation.lifespan.get", entity.getDisplayName(),
                    LifespanService.display(value.remaining(), value.total(), ReincarnationServerConfig.INSTANCE.lifespan.ticksPerYear.getValue())), false);
        }
        return entities.size();
    }

    private static int write(CommandContext<CommandSourceStack> context, boolean set) throws CommandSyntaxException {
        int changed = 0;
        for (Entity entity : EntityArgument.getEntities(context, "targets")) {
            if (!(entity instanceof LivingEntity living)) continue;
            long ticks = LongArgumentType.getLong(context, "ticks");
            Result<Void> result = set ? LifespanApi.set(living, ticks) : LifespanApi.add(living, ticks);
            if (report(context.getSource(), result, entity.getDisplayName())) changed++;
        }
        return changed;
    }

    private static int act(CommandContext<CommandSourceStack> context, boolean rebirth) throws CommandSyntaxException {
        int changed = 0;
        for (Entity entity : EntityArgument.getEntities(context, "targets")) {
            if (!(entity instanceof LivingEntity living)) continue;
            Result<?> result = rebirth ? LifespanApi.requestRebirth(living, RebirthMode.CONFIGURED, Component.translatable("reason.reincarnation.rebirth"))
                    : LifespanApi.unlimited(living);
            if (report(context.getSource(), result, entity.getDisplayName())) changed++;
        }
        return changed;
    }

    private static boolean report(CommandSourceStack source, Result<?> result, Component target) {
        if (result.getFailure().isPresent()) {
            source.sendFailure(result.getFailure().orElseThrow().message());
            return false;
        }
        source.sendSuccess(() -> Component.translatable("command.reincarnation.lifespan.changed", target), true);
        return true;
    }
}
