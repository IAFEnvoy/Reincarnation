package com.iafenvoy.reincarnation.lifespan;

import com.iafenvoy.reincarnation.Reincarnation;
import com.iafenvoy.reincarnation.api.RebirthMode;
import com.iafenvoy.reincarnation.api.ReincarnationApi;
import com.iafenvoy.reincarnation.api.ReincarnationException;
import com.iafenvoy.reincarnation.api.ReincarnationRequest;
import com.iafenvoy.reincarnation.config.ReincarnationServerConfig;
import com.iafenvoy.reincarnation.config.ReincarnationServerConfig.LifespanOutcome;
import com.iafenvoy.reincarnation.config.ReincarnationServerConfig.PlayerDeathMode;
import com.iafenvoy.reincarnation.event.LifeSpanEndEvent;
import com.iafenvoy.reincarnation.event.LifeSpanRebirthEvent;
import com.iafenvoy.reincarnation.lifecycle.AttachmentRetention;
import com.iafenvoy.reincarnation.lifecycle.ReincarnationService;
import com.iafenvoy.reincarnation.registry.ReincarnationAttachments;
import com.iafenvoy.reincarnation.storage.Generations;
import com.iafenvoy.saturn.runtime.ability.AbilityGrantService;
import com.iafenvoy.saturn.runtime.ability.PassiveAttributeService;
import com.iafenvoy.saturn.runtime.resulting.Failures;
import com.iafenvoy.saturn.runtime.resulting.Result;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.GameType;
import net.neoforged.bus.api.Event;
import net.neoforged.neoforge.common.NeoForge;

import java.util.*;

public final class LifespanService {
    public static final long UNACCOUNTED = -1L;
    public static final ResourceKey<DamageType> DAMAGE_TYPE = ResourceKey.create(Registries.DAMAGE_TYPE, Identifier.fromNamespaceAndPath(Reincarnation.MOD_ID, "lifespan"));
    private static final Map<UUID, Pending> PENDING = new LinkedHashMap<>();
    private static final Set<UUID> EXPIRING = new HashSet<>();
    private static final Set<UUID> REBIRTHING = new HashSet<>();

    private LifespanService() {
    }

    private record Pending(LivingEntity entity, long generation, RebirthMode mode, Component reason, boolean expiry) {
    }

    public static Lifespan value(Entity entity) {
        LifespanAttachment ledger = existing(entity);
        return ledger == null ? Lifespan.UNACCOUNTED : ledger.value();
    }

    public static long remaining(Entity entity) {
        return value(entity).remaining();
    }

    public static long total(Entity entity) {
        return value(entity).total();
    }

    private static boolean server(LivingEntity entity) {
        if (!(entity.level() instanceof ServerLevel level)) return false;
        if (!level.getServer().isSameThread())
            throw new IllegalStateException("Lifespan writes require the server thread");
        return true;
    }

    public static Result<Void> set(LivingEntity entity, long ticks) {
        if (!server(entity)) return Result.failure(Failures.SERVER_ONLY);
        if (ticks < 0) return Result.failure(Failures.INVALID_VALUE);
        write(entity, new Lifespan(ticks, ticks));
        return Result.success();
    }

    public static Result<Void> add(LivingEntity entity, long ticks) {
        if (!server(entity)) return Result.failure(Failures.SERVER_ONLY);
        if (ticks == 0) return Result.success();
        Lifespan old = value(entity);
        long base = old.total() < 0 ? ReincarnationServerConfig.INSTANCE.lifespan.baseLifespan.getValue() : old.total();
        long remaining = old.total() < 0 ? base : Math.max(0, old.remaining());
        write(entity, new Lifespan(Math.max(0, saturatedAdd(remaining, ticks)), ticks > 0 ? saturatedAdd(base, ticks) : base));
        return Result.success();
    }

    public static Result<Void> unlimited(LivingEntity entity) {
        if (!server(entity)) return Result.failure(Failures.SERVER_ONLY);
        write(entity, Lifespan.CLOSED);
        return Result.success();
    }

    public static boolean seed(LivingEntity entity) {
        if (!server(entity) || !ReincarnationServerConfig.INSTANCE.lifespan.enabled.getValue()) return false;
        long base = ReincarnationServerConfig.INSTANCE.lifespan.baseLifespan.getValue();
        if (base <= 0 || value(entity).total() >= 0) return false;
        write(entity, new Lifespan(base, base));
        return true;
    }

    public static boolean settle(Entity entity) {
        ReincarnationServerConfig.Lifespan settings = ReincarnationServerConfig.INSTANCE.lifespan;
        if (!(entity instanceof LivingEntity living) || !server(living) || !settings.enabled.getValue() || !living.isAlive())
            return false;
        if (entity instanceof ServerPlayer player && (player.isCreative() && !settings.ageCreative.getValue()
                || player.isSpectator() && !settings.ageSpectator.getValue())) return false;
        if (PENDING.containsKey(entity.getUUID()) || EXPIRING.contains(entity.getUUID())) return false;
        migrate(entity);
        if (entity instanceof ServerPlayer || existing(entity) != null) seed(living);
        Lifespan old = value(entity);
        if (!old.finite()) return false;
        write(living, new Lifespan(Math.max(0, old.remaining() - settings.agePerSettle.getValue()), old.total()));
        if (remaining(entity) == 0 && EXPIRING.add(entity.getUUID())) {
            try {
                expire(living);
            } finally {
                EXPIRING.remove(entity.getUUID());
            }
        }
        return true;
    }

    private static void expire(LivingEntity entity) {
        LifespanAttachment ledger = entity.getData(ReincarnationAttachments.LIFESPAN);
        boolean cancelled = NeoForge.EVENT_BUS.post(new LifeSpanEndEvent.Pre(entity, ledger)).isCanceled();
        if (!value(entity).expired()) return;
        if (cancelled) {
            write(entity, Lifespan.CLOSED);
            return;
        }
        LifespanAttachment expired = new LifespanAttachment(value(entity));
        LifespanOutcome outcome = ReincarnationServerConfig.INSTANCE.lifespan.onExpire.getValue();
        switch (outcome) {
            case NONE -> write(entity, Lifespan.CLOSED);
            case DEATH -> {
                if (entity instanceof ServerPlayer player && ReincarnationServerConfig.INSTANCE.lifespan.playerDeathMode.getValue() == PlayerDeathMode.SPECTATOR) {
                    player.setGameMode(GameType.SPECTATOR);
                    player.sendSystemMessage(Component.translatable("message.reincarnation.lifespan.deceased"));
                } else {
                    kill(entity);
                    if (entity.isAlive()) write(entity, Lifespan.CLOSED);
                }
            }
            case REINCARNATE -> {
                if (entity instanceof ServerPlayer && resolved(RebirthMode.CONFIGURED) == RebirthMode.NEW_LIFE) {
                    enqueue(entity, RebirthMode.NEW_LIFE, Component.translatable("reason.reincarnation.lifespan"), true);
                    return;
                }
                Result<LivingEntity> result = rebirth(entity, RebirthMode.RESET, Component.translatable("reason.reincarnation.lifespan"));
                if (result.getFailure().isPresent()) {
                    write(entity, Lifespan.CLOSED);
                    return;
                }
            }
        }
        notify(new LifeSpanEndEvent.Post(entity, expired, outcome));
    }

    public static Result<Void> reincarnate(LivingEntity entity) {
        return rebirth(entity, RebirthMode.CONFIGURED, Component.translatable("reason.reincarnation.rebirth")).mapData(_ -> (Void) null);
    }

    public static Result<LivingEntity> rebirth(LivingEntity entity, RebirthMode requested, Component reason) {
        if (!server(entity)) return Result.failure(Failures.SERVER_ONLY);
        if (!REBIRTHING.add(entity.getUUID())) return Result.failure(Failures.ALREADY_ACTIVE);
        try {
            RebirthMode mode = entity instanceof ServerPlayer ? resolved(requested) : RebirthMode.RESET;
            migrate(entity);
            LifespanAttachment ledger = entity.getData(ReincarnationAttachments.LIFESPAN);
            if (NeoForge.EVENT_BUS.post(new LifeSpanRebirthEvent.Pre(entity, ledger, mode)).isCanceled())
                return Result.failure(Failures.CANCELLED);
            LivingEntity fresh = entity;
            if (mode == RebirthMode.NEW_LIFE) {
                fresh = ReincarnationApi.reincarnate((ServerPlayer) entity, ReincarnationRequest.atSpawn((ServerPlayer) entity, reason));
            } else {
                AttachmentRetention.reset(entity, ReincarnationServerConfig.INSTANCE.resetPolicy());
                NeoForge.EVENT_BUS.post(new LifeSpanRebirthEvent.Reset(entity, ledger));
                AbilityGrantService.recalculate(entity);
                PassiveAttributeService.reconcile(entity);
                long base = ReincarnationServerConfig.INSTANCE.lifespan.baseLifespan.getValue();
                write(entity, base > 0 ? new Lifespan(base, base) : Lifespan.CLOSED);
                if (ReincarnationServerConfig.INSTANCE.lifespan.killOnReset.getValue()) kill(entity);
            }
            notify(new LifeSpanRebirthEvent.Post(fresh, fresh.getData(ReincarnationAttachments.LIFESPAN), mode));
            if (fresh instanceof ServerPlayer player)
                player.sendSystemMessage(Component.translatable("message.reincarnation.lifespan.reborn"));
            return Result.success(fresh);
        } catch (ReincarnationException failure) {
            if (entity instanceof ServerPlayer player && ReincarnationService.get().pending(player.getUUID()).isPresent())
                throw failure;
            return Result.failure(switch (failure.getMessage()) {
                case "error.reincarnation.canceled" -> Failures.CANCELLED;
                case "error.reincarnation.busy" -> Failures.ALREADY_ACTIVE;
                case "error.reincarnation.offline" -> Failures.NOT_APPLICABLE;
                default -> Failures.UNKNOWN;
            });
        } finally {
            REBIRTHING.remove(entity.getUUID());
        }
    }

    private static RebirthMode resolved(RebirthMode mode) {
        if (mode != RebirthMode.CONFIGURED) return mode;
        RebirthMode configured = ReincarnationServerConfig.INSTANCE.lifespan.playerRebirthMode.getValue();
        return configured == RebirthMode.CONFIGURED ? RebirthMode.NEW_LIFE : configured;
    }

    public static Result<Void> requestRebirth(LivingEntity entity, RebirthMode mode, Component reason) {
        if (!server(entity)) return Result.failure(Failures.SERVER_ONLY);
        if (REBIRTHING.contains(entity.getUUID())) return Result.failure(Failures.ALREADY_ACTIVE);
        return enqueue(entity, entity instanceof ServerPlayer ? resolved(mode) : RebirthMode.RESET, reason, false);
    }

    private static Result<Void> enqueue(LivingEntity entity, RebirthMode mode, Component reason, boolean expiry) {
        long generation = entity instanceof ServerPlayer player ? Generations.current(player.level().getServer(), player.getUUID()) : -1;
        Pending entry = new Pending(entity, generation, mode, reason.copy(), expiry);
        return PENDING.putIfAbsent(entity.getUUID(), entry) == null ? Result.success() : Result.failure(Failures.ALREADY_ACTIVE);
    }

    public static void flush() {
        List<Pending> entries = List.copyOf(PENDING.values());
        for (Pending pending : entries) {
            if (!PENDING.remove(pending.entity.getUUID(), pending)) continue;
            LivingEntity old = pending.entity;
            if (old.isRemoved() || !old.isAlive()) continue;
            if (old instanceof ServerPlayer player && (player.level().getServer().getPlayerList().getPlayer(player.getUUID()) != player
                    || Generations.current(player.level().getServer(), player.getUUID()) != pending.generation))
                continue;
            if (pending.expiry && !value(old).expired()) continue;
            LifespanAttachment ledger = old.getData(ReincarnationAttachments.LIFESPAN);
            try {
                Result<LivingEntity> result = rebirth(old, pending.mode, pending.reason);
                if (result.getFailure().isPresent()) {
                    if (pending.expiry && remaining(old) == 0) write(old, Lifespan.CLOSED);
                    notify(new LifeSpanRebirthEvent.Failed(old, ledger, pending.mode, result.getFailure().orElseThrow()));
                } else if (pending.expiry) {
                    notify(new LifeSpanEndEvent.Post(old, new LifespanAttachment(ledger.value()), LifespanOutcome.REINCARNATE));
                }
            } catch (RuntimeException failure) {
                // A committed transaction owns recovery; do not change its old player or retry it next tick.
                boolean committed = old instanceof ServerPlayer player && ReincarnationService.get().pending(player.getUUID()).isPresent();
                if (!committed && pending.expiry && remaining(old) == 0) write(old, Lifespan.CLOSED);
                Reincarnation.LOGGER.error("Queued rebirth failed for {}", old.getUUID(), failure);
                notify(new LifeSpanRebirthEvent.Failed(old, ledger, pending.mode, Failures.UNKNOWN));
            }
        }
    }

    public static void clear() {
        PENDING.clear();
        EXPIRING.clear();
        REBIRTHING.clear();
    }

    private static void notify(Event event) {
        try {
            NeoForge.EVENT_BUS.post(event);
        } catch (RuntimeException failure) {
            Reincarnation.LOGGER.error("Lifespan notification failed", failure);
        }
    }

    public static void kill(LivingEntity entity) {
        if (!(entity.level() instanceof ServerLevel level) || !entity.isAlive()) return;
        Optional<Holder.Reference<DamageType>> type = level.registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).get(DAMAGE_TYPE);
        DamageSource source = type.map(DamageSource::new).orElseGet(() -> entity.damageSources().genericKill());
        entity.hurtServer(level, source, Float.MAX_VALUE);
    }

    public static void migrate(Entity entity) {
        LifespanAttachment legacy = entity.getExistingData(ReincarnationAttachments.LEGACY_LIFESPAN).orElse(null);
        if (legacy == null) return;
        if (!entity.hasData(ReincarnationAttachments.LIFESPAN))
            entity.setData(ReincarnationAttachments.LIFESPAN, new LifespanAttachment(legacy.value()));
        entity.removeData(ReincarnationAttachments.LEGACY_LIFESPAN);
    }

    private static LifespanAttachment existing(Entity entity) {
        return entity.getExistingData(ReincarnationAttachments.LIFESPAN)
                .or(() -> entity.getExistingData(ReincarnationAttachments.LEGACY_LIFESPAN)).orElse(null);
    }

    static void write(LivingEntity entity, Lifespan next) {
        migrate(entity);
        entity.getData(ReincarnationAttachments.LIFESPAN).setLifespan(next.remaining(), next.total());
        entity.syncData(ReincarnationAttachments.LIFESPAN);
        if (entity instanceof ServerPlayer player) warn(player);
    }

    static void warn(ServerPlayer player) {
        LifespanAttachment ledger = existing(player);
        if (ledger == null) return;
        Lifespan value = ledger.value();
        ReincarnationServerConfig.Lifespan settings = ReincarnationServerConfig.INSTANCE.lifespan;
        double fraction = settings.warningFraction.getValue();
        boolean low = settings.enabled.getValue() && value.finite() && value.total() > 0 && fraction > 0
                && (double) value.remaining() / value.total() <= fraction;
        boolean sent = ledger.warningSent;
        ledger.warningSent = low;
        if (low && !sent)
            player.sendSystemMessage(Component.translatable("message.reincarnation.lifespan.warning",
                    display(value.remaining(), value.total(), settings.ticksPerYear.getValue())));
    }

    public static Component display(long remaining, long total, int ticksPerYear) {
        if (remaining < 0) return Component.translatable("info.reincarnation.lifespan.unlimited");
        long age = total - remaining;
        if (ticksPerYear <= 1) return Component.translatable("info.reincarnation.lifespan.ticks", age, total);
        return Component.translatable("info.reincarnation.lifespan.value",
                String.format(Locale.ROOT, "%.1f", (double) age / ticksPerYear),
                String.format(Locale.ROOT, "%.1f", (double) total / ticksPerYear));
    }

    private static long saturatedAdd(long value, long delta) {
        long sum = value + delta;
        return ((value ^ sum) & (delta ^ sum)) < 0 ? (delta > 0 ? Long.MAX_VALUE : Long.MIN_VALUE) : sum;
    }
}
