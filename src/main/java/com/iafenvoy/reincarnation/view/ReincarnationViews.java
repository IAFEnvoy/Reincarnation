package com.iafenvoy.reincarnation.view;

import com.iafenvoy.reincarnation.Reincarnation;
import com.iafenvoy.reincarnation.data.information.getter.LifeGetter;
import com.iafenvoy.reincarnation.data.information.getter.LifeSourceGetter;
import com.iafenvoy.reincarnation.lifecycle.SoulRecord;
import com.iafenvoy.saturn.data.information.InformationGroup;
import com.iafenvoy.saturn.data.information.PlayerViewSnapshot;
import com.iafenvoy.saturn.data.information.PlayerViews;
import com.iafenvoy.saturn.data.information.getter.EntityValueGetters;
import com.iafenvoy.saturn.data.information.model.*;
import com.iafenvoy.saturn.registry.SaturnResourceKeys;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class ReincarnationViews {
    public static final Identifier SOURCE = id("life");
    public static final LifeGetter LIFE = LifeGetter.INSTANCE;
    public static final ResourceKey<InformationGroup> GROUP = ResourceKey.create(SaturnResourceKeys.INFORMATION_GROUP, SOURCE);

    private ReincarnationViews() {
    }

    public static Holder<InformationGroup> group(HolderLookup.Provider registries) {
        return registries.lookupOrThrow(SaturnResourceKeys.INFORMATION_GROUP).getOrThrow(GROUP);
    }

    public static PlayerViewRef reference(UUID account, long generation) {
        if (generation < 0) throw new IllegalArgumentException("Negative generation");
        return new PlayerViewRef(account, SOURCE, "generation/" + generation);
    }

    public static PlayerViewSnapshot capture(ServerPlayer player, SoulRecord record, String statistics, String advancements) {
        PlayerViewMeta meta = meta(record, record.endedAt() == 0 ? PlayerViewMeta.SourceKind.LIVE : PlayerViewMeta.SourceKind.ARCHIVE, Optional.of(player.level().getGameTime()));
        PlayerViewSnapshot.Builder builder = PlayerViews.parse(player, meta).toBuilder();
        builder.put(LIFE, record);
        builder.put(LifeSourceGetter.INSTANCE, meta.sourceKind());
        builder.put(EntityValueGetters.STATISTICS, statistics);
        builder.put(EntityValueGetters.ADVANCEMENTS, advancements);
        return builder.build();
    }

    public static PlayerViewSnapshot summary(HolderLookup.Provider registries, SoulRecord record) {
        PlayerViewSnapshot.Builder builder = PlayerViewSnapshot.builder(meta(record, PlayerViewMeta.SourceKind.SUMMARY, Optional.empty()), registries.createSerializationContext(NbtOps.INSTANCE));
        builder.put(LIFE, record, ValueOrigin.SAVED);
        builder.put(LifeSourceGetter.INSTANCE, PlayerViewMeta.SourceKind.SUMMARY, ValueOrigin.SAVED);
        builder.put(EntityValueGetters.PROFILE, new PlayerViewData.Profile(record.name(), Component.literal(record.name()), Map.of()), ValueOrigin.SAVED);
        builder.put(EntityValueGetters.GAME_MODE, record.gameMode(), ValueOrigin.SAVED);
        return builder.build();
    }

    public static PlayerViewSnapshot archive(HolderLookup.Provider registries, SoulRecord record, CompoundTag archive) {
        Optional<PlayerViewSnapshot> recorded = archive.contains("player_view") ?
                PlayerViewSnapshot.CODEC.parse(registries.createSerializationContext(NbtOps.INSTANCE), archive.get("player_view")).resultOrPartial(s -> Reincarnation.LOGGER.error("Failed to decode player_view {}", s)) : Optional.empty();
        if (recorded.isPresent() && !recorded.get().meta().ref().equals(reference(record.account(), record.generation())))
            throw new IllegalStateException("Archived view belongs to a different life");
        if (!archive.contains("player")) return recorded.orElseGet(() -> summary(registries, record));
        SavedPlayerViewContext context = new SavedPlayerViewContext(meta(record, PlayerViewMeta.SourceKind.ARCHIVE, Optional.empty()),
                registries, archive.getCompound("player").orElseThrow(), archive.getString("stats"), archive.getString("advancements"));
        PlayerViewSnapshot.Builder builder = PlayerViews.parse(context, recorded).toBuilder();
        if (!builder.contains(LIFE)) builder.put(LIFE, record, ValueOrigin.SAVED);
        if (!builder.contains(EntityValueGetters.PROFILE))
            builder.put(EntityValueGetters.PROFILE, new PlayerViewData.Profile(record.name(), Component.literal(record.name()), Map.of()), ValueOrigin.SAVED);
        if (!builder.contains(EntityValueGetters.CARRIED_ITEM)) {
            if (archive.contains("carried")) {
                Optional<PlayerViewItem> item = archive.getCompound("carried").flatMap(tag -> PlayerViewItem.saved(tag, registries));
                if (item.isPresent())
                    builder.put(EntityValueGetters.CARRIED_ITEM, Map.of("cursor", item.get()), ValueOrigin.SAVED);
                else builder.missing(EntityValueGetters.CARRIED_ITEM, MissingReason.INVALID);
            } else builder.put(EntityValueGetters.CARRIED_ITEM, Map.of(), ValueOrigin.SAVED);
        }
        if (!builder.contains(EntityValueGetters.SCORES) && archive.contains("scores")) {
            try {
                CompoundTag saved = archive.getCompound("scores").orElseThrow();
                Map<String, Integer> scores = new LinkedHashMap<>();
                for (String key : saved.keySet()) scores.put(key, saved.getInt(key).orElseThrow());
                builder.put(EntityValueGetters.SCORES, scores, ValueOrigin.SAVED);
            } catch (RuntimeException failure) {
                builder.missing(EntityValueGetters.SCORES, MissingReason.INVALID);
            }
        }
        if (!builder.contains(EntityValueGetters.TEAM)) {
            if (archive.contains("team") && archive.getString("team").isEmpty())
                builder.missing(EntityValueGetters.TEAM, MissingReason.INVALID);
            else builder.put(EntityValueGetters.TEAM, archive.getString("team").orElse(""), ValueOrigin.SAVED);
        }
        return builder.build();
    }

    private static PlayerViewMeta meta(SoulRecord record, PlayerViewMeta.SourceKind kind, Optional<Long> gameTime) {
        UUID snapshot = record.endedAt() > 0 && !record.transactionId().isEmpty() ? UUID.fromString(record.transactionId()) : UUID.randomUUID();
        return new PlayerViewMeta(reference(record.account(), record.generation()), snapshot, kind,
                record.observedAt() > 0 ? Optional.of(record.observedAt()) : Optional.empty(), gameTime, System.currentTimeMillis());
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(Reincarnation.MOD_ID, path);
    }
}
