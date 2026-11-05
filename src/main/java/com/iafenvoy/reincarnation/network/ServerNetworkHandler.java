package com.iafenvoy.reincarnation.network;

import com.iafenvoy.reincarnation.Reincarnation;
import com.iafenvoy.reincarnation.data.information.LifeViewOpenScreenData;
import com.iafenvoy.reincarnation.lifecycle.ReincarnationService;
import com.iafenvoy.reincarnation.lifecycle.SoulRecord;
import com.iafenvoy.reincarnation.network.payload.LifeViewRefreshC2SPayload;
import com.iafenvoy.reincarnation.network.payload.LifeViewRefreshS2CPayload;
import com.iafenvoy.reincarnation.storage.Generations;
import com.iafenvoy.reincarnation.view.ReincarnationViews;
import com.iafenvoy.saturn.data.information.PlayerInformation;
import com.iafenvoy.saturn.data.information.PlayerViewSnapshot;
import com.iafenvoy.saturn.data.information.model.InformationDocument;
import io.netty.buffer.Unpooled;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.Optional;

public final class ServerNetworkHandler {
    static void onLifeViewRefresh(LifeViewRefreshC2SPayload payload, IPayloadContext context) {
        refresh((ServerPlayer) context.player(), payload);
    }

    public static void refresh(ServerPlayer viewer, LifeViewRefreshC2SPayload request) {
        Optional<InformationDocument> document = Optional.empty();
        Optional<LifeViewOpenScreenData.Life> life = Optional.empty();
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), viewer.registryAccess());
        try {
            if (viewer.getUUID().equals(request.account()) || Commands.<CommandSourceStack>hasPermission(Commands.LEVEL_GAMEMASTERS).test(viewer.createCommandSourceStack())) {
                MinecraftServer server = viewer.level().getServer();
                ReincarnationService service = ReincarnationService.get();
                boolean current = Generations.current(server, request.account()) == request.generation();
                ServerPlayer target = server.getPlayerList().getPlayer(request.account());
                if (!current || target != null && service.canSave(target)) {
                    Optional<PlayerViewSnapshot> view = service.view(request.account(), request.generation());
                    if (view.isPresent()) {
                        SoulRecord record = view.get().read(ReincarnationViews.LIFE).value().orElseThrow();
                        life = Optional.of(new LifeViewOpenScreenData.Life(record.generation(), record.endedAt(), record.reason()));
                        document = Optional.of(PlayerInformation.collectAll(view.get()));
                    }
                }
            }
            LifeViewRefreshS2CPayload response = new LifeViewRefreshS2CPayload(request.requestId(), request.account(), request.generation(), life, document);
            LifeViewRefreshS2CPayload.STREAM_CODEC.encode(buffer, response);
            PacketDistributor.sendToPlayer(viewer, response);
        } catch (RuntimeException failure) {
            Reincarnation.LOGGER.warn("Cannot refresh life {} of {}", request.generation(), request.account(), failure);
            PacketDistributor.sendToPlayer(viewer, new LifeViewRefreshS2CPayload(request.requestId(), request.account(), request.generation(), Optional.empty(), Optional.empty()));
        } finally {
            buffer.release();
        }
    }
}
