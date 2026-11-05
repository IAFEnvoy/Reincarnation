package com.iafenvoy.reincarnation.lifecycle;

import com.iafenvoy.reincarnation.api.ReincarnationException;
import com.iafenvoy.reincarnation.api.ResetPolicy;
import com.iafenvoy.reincarnation.mixin.GameModeResetAccess;
import com.iafenvoy.reincarnation.reference.ReferencePersistence;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.RemoteChatSession;
import net.minecraft.network.protocol.game.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stat;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.attachment.AttachmentSync;

import java.util.Map;
import java.util.Set;

final class PlayerInheritance {
    private PlayerInheritance() {
    }

    static void copyRuntime(ServerPlayer player, ServerPlayer old) {
        RemoteChatSession session = old.getChatSession();
        if (session != null) player.setChatSession(session);
        player.setChunkTrackingView(old.getChunkTrackingView());
        player.requestDebugSubscriptions(old.debugSubscriptions());
        player.setTabListHeaderFooter(old.getTabListHeader(), old.getTabListFooter());
    }

    // Both normal reincarnation and recovery start with a newly constructed, unloaded player.
    static void apply(ServerPlayer player, LifeTransaction tx, CompoundTag archive) {
        ResetPolicy policy = tx.request().policy();
        GameType mode = policy.keepGameMode()
                ? GameType.byName(archive.getCompound("record").orElseThrow().getString("game_mode").orElseThrow())
                : player.level().getServer().getDefaultGameType();
        if (player.gameMode.getGameModeForPlayer() != mode && !player.setGameMode(mode))
            throw new ReincarnationException("error.reincarnation.game_mode");
        ((GameModeResetAccess) player.gameMode).reincarnation$mode(mode, null);
        mode.updatePlayerAbilities(player.getAbilities());
        player.onUpdateAbilities();

        // Consume the complete payload's paths before extracting persistent-data/attachment subtrees.
        CompoundTag oldData = (CompoundTag) ReferencePersistence.restore(NbtOps.INSTANCE, archive.getCompound("player").orElseThrow().copy());
        if (!policy.resetUnknownModData())
            oldData.getCompound("NeoForgeData").ifPresent(data -> player.getPersistentData().merge(data));
        AttachmentRetention.restore(player, tx, archive, oldData);
        if (policy.resetScores()) player.level().getScoreboard().resetAllPlayerScores(player);
        if (policy.resetTeam()) player.level().getScoreboard().removePlayerFromTeam(player.getScoreboardName());
    }

    @SuppressWarnings("UnstableApiUsage")
    static void synchronize(ServerPlayer player, Map<Stat<?>, Integer> oldStats) {
        player.inventoryMenu.broadcastFullState();
        player.connection.send(new ClientboundSetHeldSlotPacket(player.getInventory().getSelectedSlot()));
        player.connection.send(new ClientboundSetCameraPacket(player));
        player.connection.send(new ClientboundUpdateAttributesPacket(player.getId(), player.getAttributes().getSyncableAttributes()));
        player.connection.send(new ClientboundSetHealthPacket(player.getHealth(), player.getFoodData().getFoodLevel(), player.getFoodData().getSaturationLevel()));
        player.connection.send(new ClientboundSetExperiencePacket(player.experienceProgress, player.totalExperience, player.experienceLevel));
        Object2IntOpenHashMap<Stat<?>> values = new Object2IntOpenHashMap<>();
        oldStats.keySet().forEach(stat -> values.put(stat, 0));
        ((ProgressAccess) player.getStats()).reincarnation$values().forEach((stat, value) -> values.put(stat, value.intValue()));
        player.connection.send(new ClientboundAwardStatsPacket(values));
        // Vanilla does not emit the first advancement packet when the new book has no visible entries.
        player.connection.send(new ClientboundUpdateAdvancementsPacket(true, Set.of(), Set.of(), Map.of(), false));
        player.getAdvancements().flushDirty(player, false);
        player.getRecipeBook().sendInitialRecipeBook(player);
        player.onUpdateAbilities();
        AttachmentSync.syncInitialPlayerAttachments(player);
    }
}
