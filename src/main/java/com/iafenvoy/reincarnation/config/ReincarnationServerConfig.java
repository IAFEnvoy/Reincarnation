package com.iafenvoy.reincarnation.config;

import com.iafenvoy.jupiter.config.container.AutoInitConfigContainer;
import com.iafenvoy.jupiter.config.entry.*;
import com.iafenvoy.reincarnation.Reincarnation;
import com.iafenvoy.reincarnation.api.RebirthMode;
import com.iafenvoy.reincarnation.api.ResetPolicy;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.Locale;

public final class ReincarnationServerConfig extends AutoInitConfigContainer {
    public static final ReincarnationServerConfig INSTANCE = new ReincarnationServerConfig();

    public final Lifecycle lifecycle = new Lifecycle();
    public final Reset reset = new Reset();
    public final Query query = new Query();
    public final Lifespan lifespan = new Lifespan();

    private ReincarnationServerConfig() {
        super(Identifier.fromNamespaceAndPath(Reincarnation.MOD_ID, "server"), "config.reincarnation.server",
                "./config/reincarnation/reincarnation-server.json");
    }

    // Capture settings in the request so recovery never depends on later config changes.
    public ResetPolicy resetPolicy() {
        return new ResetPolicy(this.reset.resetScores.getValue(), this.reset.resetTeam.getValue(),
                this.reset.resetUnknownModData.getValue(), this.reset.keepGameMode.getValue());
    }

    public enum LifespanOutcome {NONE, DEATH, REINCARNATE}

    public enum PlayerDeathMode {KILL, SPECTATOR}

    public static final class Lifespan extends AutoInitConfigCategoryBase {
        public final BooleanEntry enabled = BooleanEntry.builder("config.reincarnation.server.lifespan.enabled", false).key("enabled").tooltip("config.reincarnation.server.lifespan.enabled.tooltip").build();
        public final LongEntry baseLifespan = LongEntry.builder("config.reincarnation.server.lifespan.base_lifespan", 1_440_000L).key("base_lifespan").tooltip("config.reincarnation.server.lifespan.base_lifespan.tooltip").range(0L, Long.MAX_VALUE).build();
        public final IntegerEntry settleInterval = IntegerEntry.builder("config.reincarnation.server.lifespan.settle_interval", 20).key("settle_interval").tooltip("config.reincarnation.server.lifespan.settle_interval.tooltip").range(1, 72_000).build();
        public final IntegerEntry agePerSettle = IntegerEntry.builder("config.reincarnation.server.lifespan.age_per_settle", 20).key("age_per_settle").tooltip("config.reincarnation.server.lifespan.age_per_settle.tooltip").range(0, 72_000).build();
        public final EnumEntry<LifespanOutcome> onExpire = EnumEntry.builder("config.reincarnation.server.lifespan.on_expire", LifespanOutcome.DEATH).key("on_expire").tooltip("config.reincarnation.server.lifespan.on_expire.tooltip").nameProvider(v -> Component.translatable("config.reincarnation.server.lifespan.on_expire." + v.name().toLowerCase(Locale.ROOT))).build();
        public final EnumEntry<PlayerDeathMode> playerDeathMode = EnumEntry.builder("config.reincarnation.server.lifespan.player_death_mode", PlayerDeathMode.KILL).key("player_death_mode").tooltip("config.reincarnation.server.lifespan.player_death_mode.tooltip").nameProvider(v -> Component.translatable("config.reincarnation.server.lifespan.player_death_mode." + v.name().toLowerCase(Locale.ROOT))).build();
        public final EnumEntry<RebirthMode> playerRebirthMode = EnumEntry.builder("config.reincarnation.server.lifespan.player_rebirth_mode", RebirthMode.NEW_LIFE).key("player_rebirth_mode").tooltip("config.reincarnation.server.lifespan.player_rebirth_mode.tooltip").nameProvider(v -> Component.translatable("config.reincarnation.server.lifespan.rebirth_mode." + v.name().toLowerCase(Locale.ROOT))).build();
        public final DoubleEntry warningFraction = DoubleEntry.builder("config.reincarnation.server.lifespan.warning_fraction", 0.1D).key("warning_fraction").tooltip("config.reincarnation.server.lifespan.warning_fraction.tooltip").range(0.0D, 1.0D).build();
        public final IntegerEntry ticksPerYear = IntegerEntry.builder("config.reincarnation.server.lifespan.ticks_per_year", 24_000).key("ticks_per_year").tooltip("config.reincarnation.server.lifespan.ticks_per_year.tooltip").range(1, Integer.MAX_VALUE).build();
        public final BooleanEntry ageCreative = BooleanEntry.builder("config.reincarnation.server.lifespan.age_creative", false).key("age_creative").tooltip("config.reincarnation.server.lifespan.age_creative.tooltip").build();
        public final BooleanEntry ageSpectator = BooleanEntry.builder("config.reincarnation.server.lifespan.age_spectator", false).key("age_spectator").tooltip("config.reincarnation.server.lifespan.age_spectator.tooltip").build();

        private Lifespan() {
            super("lifespan", "config.reincarnation.server.lifespan");
        }
    }

    public static final class Lifecycle extends AutoInitConfigCategoryBase {
        public final BooleanEntry autoReincarnateOnDeath = BooleanEntry.builder("config.reincarnation.server.lifecycle.auto_reincarnate_on_death", false)
                .key("auto_reincarnate_on_death").tooltip("config.reincarnation.server.lifecycle.auto_reincarnate_on_death.tooltip").build();
        public final BooleanEntry filterDeathDamageTypes = BooleanEntry.builder("config.reincarnation.server.lifecycle.filter_death_damage_types", false)
                .key("filter_death_damage_types").tooltip("config.reincarnation.server.lifecycle.filter_death_damage_types.tooltip").build();

        private Lifecycle() {
            super("lifecycle", "config.reincarnation.server.lifecycle");
        }
    }

    public static final class Reset extends AutoInitConfigCategoryBase {
        public final BooleanEntry resetScores = BooleanEntry.builder("config.reincarnation.server.reset.scores", ResetPolicy.DEFAULT.resetScores())
                .key("scores").tooltip("config.reincarnation.server.reset.scores.tooltip").build();
        public final BooleanEntry resetTeam = BooleanEntry.builder("config.reincarnation.server.reset.team", ResetPolicy.DEFAULT.resetTeam())
                .key("team").tooltip("config.reincarnation.server.reset.team.tooltip").build();
        public final BooleanEntry resetUnknownModData = BooleanEntry.builder("config.reincarnation.server.reset.unknown_mod_data", ResetPolicy.DEFAULT.resetUnknownModData())
                .key("unknown_mod_data").tooltip("config.reincarnation.server.reset.unknown_mod_data.tooltip").build();
        public final BooleanEntry keepGameMode = BooleanEntry.builder("config.reincarnation.server.reset.keep_game_mode", ResetPolicy.DEFAULT.keepGameMode())
                .key("keep_game_mode").tooltip("config.reincarnation.server.reset.keep_game_mode.tooltip").build();

        private Reset() {
            super("reset", "config.reincarnation.server.reset");
        }
    }

    public static final class Query extends AutoInitConfigCategoryBase {
        public final IntegerEntry historyPageSize = IntegerEntry.builder("config.reincarnation.server.query.history_page_size", 8)
                .key("history_page_size").tooltip("config.reincarnation.server.query.history_page_size.tooltip").range(1, 50).build();

        private Query() {
            super("query", "config.reincarnation.server.query");
        }
    }
}
