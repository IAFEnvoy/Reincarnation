package com.iafenvoy.reincarnation.lifecycle;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.iafenvoy.reincarnation.Reincarnation;
import com.iafenvoy.reincarnation.api.LifeNumbers;
import com.iafenvoy.reincarnation.api.ReincarnationContext;
import com.iafenvoy.reincarnation.api.ReincarnationException;
import com.iafenvoy.reincarnation.api.ReincarnationRequest;
import com.iafenvoy.reincarnation.config.ReincarnationServerConfig;
import com.iafenvoy.reincarnation.event.ReincarnationEvent;
import com.iafenvoy.reincarnation.mixin.ConnectionResetAccess;
import com.iafenvoy.reincarnation.mixin.PlayerListAccess;
import com.iafenvoy.reincarnation.registry.ReincarnationAttachments;
import com.iafenvoy.reincarnation.registry.tag.DamageTypeTags;
import com.iafenvoy.reincarnation.storage.Generations;
import com.iafenvoy.reincarnation.storage.LifeStore;
import com.iafenvoy.reincarnation.view.ReincarnationViews;
import com.iafenvoy.saturn.data.information.PlayerViewSnapshot;
import com.iafenvoy.saturn.runtime.ability.AbilityGrantService;
import com.iafenvoy.saturn.runtime.ability.PassiveAttributeService;
import com.iafenvoy.saturn.util.FileAccess;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.stats.Stat;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.SavedDataStorage;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.ScoreboardSaveData;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerRespawnPositionEvent;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Main-thread coordinator. COMMITTING records the intent to finish the same life transition.
 */
public final class ReincarnationService {
    private static @Nullable ReincarnationService instance;
    private final MinecraftServer server;
    private final LifeStore store;
    private final Map<UUID, LifeTransaction> pending = new HashMap<>();
    private final Set<UUID> busy = new HashSet<>();
    private final Set<UUID> loginPrepared = new HashSet<>();
    private final Set<UUID> savingInitialized = new HashSet<>();
    private final Map<UUID, CompoundTag> inheriting = new HashMap<>();
    private final Queue<Entity> joinedEntities = new ArrayDeque<>();

    private ReincarnationService(MinecraftServer server) {
        this.server = server;
        this.store = new LifeStore(server);
    }

    public static ReincarnationService get() {
        return Objects.requireNonNull(instance, "Reincarnation service has not started");
    }

    public static void start(MinecraftServer server) {
        instance = new ReincarnationService(server);
        instance.initialize();
    }

    public static void stop() {
        instance = null;
    }

    public LifeStore store() {
        return this.store;
    }

    public Optional<LifeTransaction> pending(UUID account) {
        return Optional.ofNullable(this.pending.get(account));
    }

    public void enqueue(Entity entity) {
        this.joinedEntities.add(entity);
    }

    public void tick() {
        for (int count = this.joinedEntities.size(); count > 0; count--) Relations.clean(this.joinedEntities.remove());
    }

    private void onThread() {
        if (!this.server.isSameThread()) throw new IllegalStateException("Reincarnation requires the server thread");
    }

    private void initialize() {
        this.onThread();
        Generations.initialize(this.server);
        Handlers.all();
        for (LifeTransaction tx : this.store.transactions()) {
            if (tx.state() == LifeTransaction.State.PREPARED)
                this.store.transaction(tx.withState(LifeTransaction.State.ABORTED));
            if (tx.state() != LifeTransaction.State.COMMITTING) continue;
            CompoundTag archive = this.store.archive(tx);
            Handlers.validate(archive.getCompound("handlers").orElseThrow());
            if (this.pending.putIfAbsent(tx.account(), tx) != null)
                throw new IllegalStateException("Multiple pending lives for " + tx.account());
            Generations.commit(this.server, tx.account(), tx.from(), tx.to());
            if (this.initialized(tx)) this.finishMetadata(tx, archive, null);
            else this.prepareLogin(tx);
        }
    }

    public Optional<ServerPlayer> automaticRespawn(ServerPlayer player, boolean keepAllPlayerData) {
        this.onThread();
        UUID account = player.getUUID();
        if (this.busy.contains(account)) return Optional.empty();
        if (this.pending.containsKey(account)) return Optional.of(player);
        if (keepAllPlayerData || !player.isDeadOrDying() || !ReincarnationServerConfig.INSTANCE.lifecycle.autoReincarnateOnDeath.getValue())
            return Optional.empty();
        if (ReincarnationServerConfig.INSTANCE.lifecycle.filterDeathDamageTypes.getValue()
                && player.getExistingData(ReincarnationAttachments.DEATH_DAMAGE_TYPE)
                .flatMap(type -> player.registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).get(type))
                .filter(type -> type.is(DamageTypeTags.REINCARNATE_ON_DEATH)).isEmpty())
            return Optional.empty();
        ServerPlayer fresh;
        try {
            Component reason = player.getExistingData(ReincarnationAttachments.DEATH_REASON)
                    .orElseGet(() -> Component.translatable("reason.reincarnation.death"));
            fresh = this.reincarnate(player, ReincarnationRequest.atSpawn(player, reason));
        } catch (RuntimeException failure) {
            Reincarnation.LOGGER.error("Automatic reincarnation failed for {}", account, failure);
            // A committed intent already disconnected the player; suppress any second vanilla respawn.
            if (this.pending.containsKey(account)) return Optional.of(player.connection.player);
            Component detail = failure instanceof ReincarnationException known ? known.component() : Component.translatable("error.reincarnation.internal");
            player.sendSystemMessage(Component.translatable("command.reincarnation.rebirth.failed", detail));
            return Optional.empty();
        }
        fresh.sendSystemMessage(Component.translatable("command.reincarnation.rebirth.success", fresh.getGameProfile().name(), LifeNumbers.display(Generations.current(this.server, account))));
        return Optional.of(fresh);
    }

    public ServerPlayer reincarnate(ServerPlayer old, ReincarnationRequest request) {
        this.onThread();
        UUID account = old.getUUID();
        if (this.pending.containsKey(account) || !this.busy.add(account))
            throw new ReincarnationException("error.reincarnation.busy");
        LifeTransaction tx;
        try {
            // Vanilla removes the corpse after 20 death ticks while its connected player waits to respawn.
            boolean removedCorpse = old.isDeadOrDying() && old.getRemovalReason() == Entity.RemovalReason.KILLED;
            if (this.server.getPlayerList().getPlayer(account) != old || old.connection.player != old || old.hasDisconnected() || !old.connection.getConnection().isConnected() || old.isRemoved() && !removedCorpse)
                throw new ReincarnationException("error.reincarnation.offline");
            if (this.server.getLevel(request.dimension()) == null)
                throw new ReincarnationException("error.reincarnation.dimension");
            long from = Generations.current(this.server, account);
            if (from == Long.MAX_VALUE) throw new ReincarnationException("error.reincarnation.generation_limit");
            UUID id = UUID.randomUUID();
            long now = System.currentTimeMillis();
            ReincarnationContext context = new ReincarnationContext(id, account, from, from + 1, request, false);
            if (NeoForge.EVENT_BUS.post(new ReincarnationEvent.Pre(old, context)).isCanceled())
                throw new ReincarnationException("error.reincarnation.canceled");
            // All snapshots come from current memory, including progress not yet autosaved.
            Map<Stat<?>, Integer> oldStats = ((ProgressAccess) old.getStats()).reincarnation$values();
            CompoundTag archive = new CompoundTag();
            archive.putInt("version", 1);
            archive.putString("minecraft", SharedConstants.getCurrentVersion().name());
            CompoundTag versions = new CompoundTag();
            ModList.get().getMods().forEach(mod -> versions.putString(mod.getModId(), mod.getVersion().toString()));
            archive.put("mods", versions);
            archive.putString("transaction", id.toString());
            archive.put("player", playerData(old));
            archive.put("kept_attachments", AttachmentRetention.capture(old, request.policy()));
            if (!old.containerMenu.getCarried().isEmpty()) archive.put("carried", ItemStack.CODEC.encodeStart(
                    old.registryAccess().createSerializationContext(NbtOps.INSTANCE), old.containerMenu.getCarried()).getOrThrow());
            archive.putString("stats", ((ProgressAccess) old.getStats()).reincarnation$json());
            archive.putString("advancements", ((AdvancementAccess) old.getAdvancements()).reincarnation$json());
            long started = LifeMarker.of(old).getLong("started").orElse(now);
            SoulRecord record = SoulRecord.capture(old, from, started, now, request.reason(), id.toString());
            archive.put("record", record.save());
            archive.put("handlers", Handlers.capture(old));
            CompoundTag scores = new CompoundTag();
            old.level().getScoreboard().listPlayerScores(old).forEach((objective, value) -> scores.putInt(objective.getName(), value));
            archive.put("scores", scores);
            if (old.getTeam() != null) archive.putString("team", old.getTeam().getName());
            archive.put("player_view", PlayerViewSnapshot.CODEC.encodeStart(old.registryAccess().createSerializationContext(NbtOps.INSTANCE),
                    ReincarnationViews.capture(old, record, archive.getString("stats").orElseThrow(), archive.getString("advancements").orElseThrow())).getOrThrow());
            String hash = this.store.archive(id, archive);
            tx = new LifeTransaction(id, account, old.getGameProfile().name(), from, from + 1, now, request, hash, LifeTransaction.State.PREPARED);
            this.store.transaction(tx);
            // No destructive action may precede this write.
            tx = tx.withState(LifeTransaction.State.COMMITTING);
            this.store.transaction(tx);
            this.pending.put(account, tx);
            Relations.beforeTransition(old);
            Generations.commit(this.server, account, from, from + 1);
            this.evict(account);
            this.seedProgress(account);
            old.setRespawnPosition(null, false);
            ServerPlayer fresh;
            this.inheriting.put(account, archive);
            try {
                fresh = this.server.getPlayerList().respawn(old, false, Entity.RemovalReason.CHANGED_DIMENSION);
            } finally {
                this.inheriting.remove(account);
            }
            fresh.connection.player = fresh;
            fresh.connection.resetPosition();
            ((ConnectionResetAccess) fresh.connection).reincarnation$restartClientLoadTimer();
            Relations.afterTransition(old);
            // All Clone/PlayerRespawnEvent listeners finish before handlers and the new-life save.
            this.complete(fresh, tx, archive, oldStats, false);
            return fresh;
        } catch (RuntimeException failure) {
            if (this.pending.containsKey(account)) this.fail(account, failure);
            throw failure;
        } finally {
            this.busy.remove(account);
        }
    }

    public boolean isInheriting(UUID account) {
        return this.inheriting.containsKey(account);
    }

    /**
     * Replaces vanilla restoreFrom only inside this service's reincarnation respawn.
     */
    public boolean inheritFrom(ServerPlayer fresh, ServerPlayer old) {
        CompoundTag archive = this.inheriting.get(old.getUUID());
        if (archive == null || !fresh.getUUID().equals(old.getUUID())) return false;
        PlayerInheritance.copyRuntime(fresh, old);
        PlayerInheritance.apply(fresh, this.pending.get(old.getUUID()), archive);
        return true;
    }

    public void respawnPosition(PlayerRespawnPositionEvent event) {
        LifeTransaction tx = this.pending.get(event.getEntity().getUUID());
        if (tx == null || !this.busy.contains(tx.account())) return;
        ReincarnationRequest request = tx.request();
        ServerLevel level = this.server.getLevel(request.dimension());
        if (level == null) throw new ReincarnationException("error.reincarnation.dimension");
        event.setCopyOriginalSpawnPosition(false);
        event.setTeleportTransition(new TeleportTransition(level, request.position(), Vec3.ZERO,
                request.yaw(), request.pitch(), TeleportTransition.DO_NOTHING));
    }

    /**
     * Invoked before both configuration-phase position selection and actual player-data loading.
     */
    public boolean emptyLoad(UUID account) {
        this.onThread();
        LifeTransaction tx = this.pending.get(account);
        if (tx == null) return false;
        Generations.commit(this.server, account, tx.from(), tx.to());
        if (this.initialized(tx)) {
            this.finishMetadata(tx, this.store.archive(tx), null);
            return false;
        }
        if (!this.loginPrepared.contains(account)) this.prepareLogin(tx);
        return true;
    }

    public void validateLoad(UUID account, Optional<CompoundTag> data) {
        if (this.pending.containsKey(account)) return;
        long current = Generations.current(this.server, account);
        if (current > 0 && (data.isEmpty() || LifeMarker.of(data.get()).getLong("generation").orElse(-1L) != current))
            throw new IllegalStateException("Refusing missing or stale player data for " + account);
    }

    public void login(ServerPlayer player) {
        this.onThread();
        LifeTransaction tx = this.pending.get(player.getUUID());
        if (tx == null) {
            this.observe(player);
            return;
        }
        if (!this.busy.add(tx.account())) return;
        try {
            Generations.commit(this.server, tx.account(), tx.from(), tx.to());
            this.complete(player, tx, this.store.archive(tx), Map.of(), true);
        } catch (RuntimeException failure) {
            this.fail(tx.account(), failure);
        } finally {
            this.busy.remove(tx.account());
        }
    }

    public void logout(ServerPlayer player) {
        if (!this.pending.containsKey(player.getUUID())) this.observe(player);
        this.loginPrepared.remove(player.getUUID());
    }

    public boolean canSave(ServerPlayer player) {
        UUID account = player.getUUID();
        if (player.isRemoved()) return false;
        ServerPlayer current = this.server.getPlayerList().getPlayer(account);
        if (current != null && current != player) return false;
        return (!this.pending.containsKey(account) || this.savingInitialized.contains(account)) && (Generations.current(this.server, account) == 0
                || LifeMarker.of(player).getLong("generation").orElse(-1L) == Generations.current(this.server, account));
    }

    public void observe(ServerPlayer player) {
        if (!this.canSave(player)) return;
        CompoundTag marker = LifeMarker.of(player);
        long started = marker.getLong("started").orElseGet(() -> this.store.current(player.getUUID()).map(SoulRecord::startedAt).orElse(System.currentTimeMillis()));
        if (!marker.contains("started"))
            LifeMarker.write(player, Generations.current(this.server, player.getUUID()), "", started);
        this.store.current(SoulRecord.capture(player, Generations.current(this.server, player.getUUID()), started, 0, Component.empty(), marker.getString("transaction").orElse("")));
    }

    public Optional<SoulRecord> current(UUID account) {
        ServerPlayer player = this.server.getPlayerList().getPlayer(account);
        if (player != null && this.canSave(player)) this.observe(player);
        return this.store.current(account);
    }

    /**
     * Server-thread data access. The caller must authorize the viewer before calling this method.
     */
    public Optional<PlayerViewSnapshot> view(UUID account, long generation) {
        this.onThread();
        if (generation < 0) throw new IllegalArgumentException("Negative generation");
        if (generation == Generations.current(this.server, account)) {
            if (this.pending.containsKey(account)) throw new ReincarnationException("error.reincarnation.busy");
            ServerPlayer player = this.server.getPlayerList().getPlayer(account);
            if (player != null && this.canSave(player)) {
                CompoundTag marker = LifeMarker.of(player);
                SoulRecord record = SoulRecord.capture(player, generation, marker.getLong("started").orElse(0L), 0, Component.empty(), marker.getString("transaction").orElse(""));
                return Optional.of(ReincarnationViews.capture(player, record, ((ProgressAccess) player.getStats()).reincarnation$json(),
                        ((AdvancementAccess) player.getAdvancements()).reincarnation$json()));
            }
            return this.store.current(account).filter(record -> record.generation() == generation)
                    .map(record -> ReincarnationViews.summary(this.server.registryAccess(), record));
        }
        return this.store.history(account, generation).map(record -> this.store.archive(record)
                .map(archive -> ReincarnationViews.archive(this.server.registryAccess(), record, archive))
                .orElseGet(() -> ReincarnationViews.summary(this.server.registryAccess(), record)));
    }

    private void complete(ServerPlayer fresh, LifeTransaction tx, CompoundTag archive, Map<Stat<?>, Integer> oldStats, boolean recovering) {
        if (recovering) PlayerInheritance.apply(fresh, tx, archive);
        Handlers.apply(fresh, tx, archive.getCompound("handlers").orElseThrow(), recovering);
        NeoForge.EVENT_BUS.post(new ReincarnationEvent.Reset(fresh, tx.context(recovering)));
        AbilityGrantService.recalculate(fresh);
        PassiveAttributeService.reconcile(fresh);
        ReincarnationRequest request = tx.request();
        ServerLevel level = this.server.getLevel(request.dimension());
        if (level == null) throw new ReincarnationException("error.reincarnation.dimension");
        if (fresh.teleport(new TeleportTransition(level, request.position(), Vec3.ZERO,
                request.yaw(), request.pitch(), TeleportTransition.DO_NOTHING)) == null)
            throw new ReincarnationException("error.reincarnation.teleport");
        LifeMarker.write(fresh, tx.to(), tx.id().toString(), tx.createdAt());
        PlayerInheritance.synchronize(fresh, oldStats);
        if (!FileAccess.JSON.save(ExtraCodecs.JSON, this.statsFile(tx.account()),
                JsonParser.parseString(((ProgressAccess) fresh.getStats()).reincarnation$json())))
            throw new IllegalStateException("Cannot save initialized statistics");
        if (!FileAccess.JSON.save(ExtraCodecs.JSON, this.advancementsFile(tx.account()),
                JsonParser.parseString(((AdvancementAccess) fresh.getAdvancements()).reincarnation$json())))
            throw new IllegalStateException("Cannot save initialized advancements");
        this.persistScoreboard(tx);
        if (!FileAccess.NBT.save(SoulRecord.CODEC, this.store.initializedRecordFile(tx.id()),
                SoulRecord.capture(fresh, tx.to(), tx.createdAt(), 0, Component.empty(), tx.id().toString())))
            throw new IllegalStateException("Cannot save initialized life summary");
        // Vanilla owns the player file format; permit only this initialized player's save.
        this.savingInitialized.add(tx.account());
        try {
            this.server.getPlayerList().getPlayerIo().save(fresh);
        } finally {
            this.savingInitialized.remove(tx.account());
        }
        if (!this.initialized(tx)) throw new ReincarnationException("error.reincarnation.verification");
        this.finishMetadata(tx, archive, fresh);
        try {
            NeoForge.EVENT_BUS.post(new ReincarnationEvent.Post(fresh, tx.context(recovering)));
        } catch (RuntimeException notificationFailure) {
            Reincarnation.LOGGER.error("Reincarnation committed; Post notification failed", notificationFailure);
        }
    }

    private void finishMetadata(LifeTransaction tx, CompoundTag archive, ServerPlayer fresh) {
        this.store.history(SoulRecord.load(archive.getCompound("record").orElseThrow()));
        if (fresh != null)
            this.store.current(SoulRecord.capture(fresh, tx.to(), tx.createdAt(), 0, Component.empty(), tx.id().toString()));
        else {
            SoulRecord initial = FileAccess.NBT.load(SoulRecord.CODEC, this.store.initializedRecordFile(tx.id()))
                    .orElseThrow(() -> new IllegalStateException("Cannot load initialized life summary"));
            if (initial.generation() != tx.to() || !initial.transactionId().equals(tx.id().toString()) || !initial.account().equals(tx.account()))
                throw new IllegalStateException("Invalid initialized-life summary");
            Optional<SoulRecord> existing = this.store.current(tx.account());
            if (existing.isEmpty() || existing.get().generation() != tx.to()) this.store.current(initial);
        }
        this.store.transaction(tx.withState(LifeTransaction.State.COMMITTED));
        this.pending.remove(tx.account());
        this.loginPrepared.remove(tx.account());
    }

    private void prepareLogin(LifeTransaction tx) {
        Generations.commit(this.server, tx.account(), tx.from(), tx.to());
        this.evict(tx.account());
        this.seedProgress(tx.account());
        this.loginPrepared.add(tx.account());
    }

    private void evict(UUID account) {
        PlayerListAccess access = (PlayerListAccess) this.server.getPlayerList();
        PlayerAdvancements advancements = access.reincarnation$advancements().remove(account);
        if (advancements != null) advancements.stopListening();
        access.reincarnation$stats().remove(account);
    }

    private void seedProgress(UUID account) {
        int version = SharedConstants.getCurrentVersion().dataVersion().version();
        JsonObject stats = new JsonObject();
        stats.add("stats", new JsonObject());
        stats.addProperty("DataVersion", version);
        JsonObject advancements = new JsonObject();
        advancements.addProperty("DataVersion", version);
        try {
            Files.createDirectories(this.statsFile(account).getParent());
            Files.createDirectories(this.advancementsFile(account).getParent());
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot create player progress directories", failure);
        }
        if (!FileAccess.JSON.save(ExtraCodecs.JSON, this.statsFile(account), stats)
                || !FileAccess.JSON.save(ExtraCodecs.JSON, this.advancementsFile(account), advancements))
            throw new IllegalStateException("Cannot initialize player progress");
    }

    private boolean initialized(LifeTransaction tx) {
        Path player = this.playerFile(tx.account(), ".dat");
        if (!Files.isRegularFile(player) || this.server.getPlayerList().getPlayerIo()
                .load(new NameAndId(tx.account(), tx.name())).filter(data -> LifeMarker.matches(data, tx)).isEmpty())
            return false;
        boolean stats = FileAccess.JSON.load(ExtraCodecs.JSON, this.statsFile(tx.account())).filter(JsonElement::isJsonObject).isPresent();
        boolean advancements = FileAccess.JSON.load(ExtraCodecs.JSON, this.advancementsFile(tx.account())).filter(JsonElement::isJsonObject).isPresent();
        if (!stats || !advancements)
            throw new IllegalStateException("Initialized life has missing/corrupt progress files");
        return true;
    }

    private void persistScoreboard(LifeTransaction tx) {
        if (!tx.request().policy().resetScores() && !tx.request().policy().resetTeam()) return;
        SavedDataStorage storage = this.server.getDataStorage();
        ScoreboardSaveData data = storage.computeIfAbsent(ScoreboardSaveData.TYPE);
        this.server.getScoreboard().storeToSaveDataIfDirty(data);
        // Vanilla owns the scoreboard's file format and pending background saves.
        storage.saveAndJoin();
    }

    private void fail(UUID account, RuntimeException failure) {
        this.loginPrepared.remove(account);
        Reincarnation.LOGGER.error("Reincarnation {} awaits recovery; partial player saves blocked", account, failure);
        ServerPlayer active = this.server.getPlayerList().getPlayer(account);
        if (active != null) {
            active.connection.player = active;
            active.getAdvancements().stopListening();
            active.connection.disconnect(Component.translatable("disconnect.reincarnation.recovery", this.pending.get(account).id().toString()));
        }
    }

    public static CompoundTag playerData(ServerPlayer player) {
        TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, player.registryAccess());
        player.saveWithoutId(output);
        CompoundTag data = output.buildResult();
        data.putInt("DataVersion", SharedConstants.getCurrentVersion().dataVersion().version());
        return data;
    }

    private Path playerFile(UUID account, String suffix) {
        return this.server.getWorldPath(LevelResource.PLAYER_DATA_DIR).resolve(account + suffix);
    }

    private Path statsFile(UUID account) {
        return this.server.getWorldPath(LevelResource.PLAYER_STATS_DIR).resolve(account + ".json");
    }

    private Path advancementsFile(UUID account) {
        return this.server.getWorldPath(LevelResource.PLAYER_ADVANCEMENTS_DIR).resolve(account + ".json");
    }
}
