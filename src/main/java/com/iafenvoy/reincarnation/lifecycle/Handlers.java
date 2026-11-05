package com.iafenvoy.reincarnation.lifecycle;

import com.iafenvoy.reincarnation.api.ReincarnationHandler;
import com.iafenvoy.reincarnation.event.RegisterReincarnationHandlersEvent;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.ModLoader;

import java.util.*;

public final class Handlers {
    private static List<ReincarnationHandler> registered;

    private Handlers() {
    }

    public static synchronized List<ReincarnationHandler> all() {
        if (registered == null) {
            Map<Identifier, ReincarnationHandler> handlers = new LinkedHashMap<>();
            ModLoader.postEvent(new RegisterReincarnationHandlersEvent(handler -> {
                if (handler.version() < 1 || handlers.putIfAbsent(handler.id(), handler) != null)
                    throw new IllegalArgumentException("Invalid/duplicate reincarnation handler " + handler.id());
            }));
            ArrayList<ReincarnationHandler> ordered = new ArrayList<>(handlers.values());
            ordered.sort(Comparator.comparingInt(ReincarnationHandler::priority).thenComparing(handler -> handler.id().toString()));
            registered = List.copyOf(ordered);
        }
        return registered;
    }

    public static CompoundTag capture(ServerPlayer player) {
        CompoundTag snapshots = new CompoundTag();
        for (ReincarnationHandler handler : all()) {
            CompoundTag entry = new CompoundTag();
            entry.putInt("version", handler.version());
            entry.put("data", handler.archive(player).copy());
            snapshots.put(handler.id().toString(), entry);
        }
        return snapshots;
    }

    public static void validate(CompoundTag snapshots) {
        Map<String, ReincarnationHandler> available = new LinkedHashMap<>();
        all().forEach(handler -> available.put(handler.id().toString(), handler));
        for (String key : snapshots.keySet()) {
            ReincarnationHandler handler = available.get(key);
            if (handler == null || snapshots.getCompound(key).orElseThrow().getInt("version").orElse(-1) != handler.version())
                throw new IllegalStateException("Missing/incompatible reincarnation handler: " + key);
        }
    }

    public static void apply(ServerPlayer fresh, LifeTransaction tx, CompoundTag snapshots, boolean recovering) {
        validate(snapshots);
        for (ReincarnationHandler handler : all()) {
            Optional<CompoundTag> entry = snapshots.getCompound(handler.id().toString());
            if (entry.isEmpty()) continue; // a handler installed after this transaction did not participate
            CompoundTag snapshot = entry.get().getCompound("data").orElseThrow().copy();
            if (recovering) handler.recover(fresh, tx.context(true), snapshot);
            else handler.reset(fresh, tx.context(false), snapshot);
        }
    }
}
