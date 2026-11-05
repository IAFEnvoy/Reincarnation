package com.iafenvoy.reincarnation.api;

import com.iafenvoy.reincarnation.lifecycle.ReincarnationService;
import com.iafenvoy.saturn.data.information.PlayerViewSnapshot;
import net.minecraft.server.level.ServerPlayer;

import java.util.Optional;
import java.util.UUID;

/**
 * Entry points for trusted callers on the logical server thread.
 */
public final class ReincarnationApi {
    private ReincarnationApi() {
    }

    /**
     * Returns the replacement player; stop using the old object.
     */
    public static ServerPlayer reincarnate(ServerPlayer player, ReincarnationRequest request) {
        return ReincarnationService.get().reincarnate(player, request);
    }

    /**
     * Uses zero-based stored generations. The caller must authorize the viewer before accessing data.
     */
    public static Optional<PlayerViewSnapshot> view(UUID account, long generation) {
        return ReincarnationService.get().view(account, generation);
    }
}
