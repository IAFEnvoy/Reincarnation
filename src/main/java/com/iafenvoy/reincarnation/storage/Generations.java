package com.iafenvoy.reincarnation.storage;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

public final class Generations {
    private static final Map<MinecraftServer, GenerationIndex> INDICES = new WeakHashMap<>();

    private Generations() {
    }

    private static synchronized GenerationIndex index(MinecraftServer server) {
        return INDICES.computeIfAbsent(server, key -> GenerationIndex.open(key.getWorldPath(LevelResource.DATA).resolve("reincarnation/generations.dat")));
    }

    public static long current(MinecraftServer server, UUID account) {
        return index(server).current(account);
    }

    public static void initialize(MinecraftServer server) {
        index(server);
    }

    /**
     * Transaction infrastructure only; gameplay callers should use ReincarnationApi.reincarnate.
     */
    public static void commit(MinecraftServer server, UUID account, long expected, long next) {
        if (!server.isSameThread()) throw new IllegalStateException("Generation commits require the server thread");
        index(server).commit(account, expected, next);
    }
}
