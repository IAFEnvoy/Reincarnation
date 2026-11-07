package com.iafenvoy.reincarnation.storage;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.util.UUID;

public final class Generations {
    private static GenerationIndex index;

    private Generations() {
    }

    private static synchronized GenerationIndex index(MinecraftServer server) {
        if (index == null) initialize(server);
        return index;
    }

    public static long current(MinecraftServer server, UUID account) {
        return index(server).current(account);
    }

    public static synchronized void initialize(MinecraftServer server) {
        index = GenerationIndex.open(server.getWorldPath(LevelResource.DATA).resolve("reincarnation/generations.dat"));
    }

    public static synchronized void stop() {
        index = null;
    }

    /**
     * Transaction infrastructure only; gameplay callers should use ReincarnationApi.reincarnate.
     */
    public static void commit(MinecraftServer server, UUID account, long expected, long next) {
        if (!server.isSameThread()) throw new IllegalStateException("Generation commits require the server thread");
        index(server).commit(account, expected, next);
    }
}
