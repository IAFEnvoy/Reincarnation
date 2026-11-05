package com.iafenvoy.reincarnation.lifecycle;

import com.iafenvoy.reincarnation.api.LifeReferences;
import com.iafenvoy.reincarnation.storage.Generations;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.level.ChunkPos;

import java.util.*;

/**
 * Gameplay consequences only. Reference serialization remains entirely generic.
 */
public final class Relations {
    private Relations() {
    }

    private static boolean stale(ServerLevel level, EntityReference<?> reference) {
        return reference != null && LifeReferences.generation(reference).orElse(0L)
                < Generations.current(level.getServer(), reference.getUUID());
    }

    public static void clean(Entity entity) {
        if (!(entity.level() instanceof ServerLevel level) || entity.isRemoved()) return;
        if (entity instanceof TamableAnimal pet && stale(level, pet.getOwnerReference())) {
            pet.setOwner(null);
            pet.setOrderedToSit(false);
            pet.setInSittingPose(false);
            pet.setTame(false, true);
        }
        if (entity instanceof AbstractHorse horse && stale(level, horse.getOwnerReference())) {
            horse.setOwner(null);
            horse.setTamed(false);
            horse.setTemper(0);
        }
        if (entity instanceof NeutralMob neutral && stale(level, neutral.getPersistentAngerTarget()))
            neutral.stopBeingAngry();
    }

    static void beforeTransition(ServerPlayer old) {
        old.closeContainer();
        old.stopRiding();
        old.ejectPassengers();
        Map<ServerLevel, Set<ChunkPos>> affected = new HashMap<>();
        for (ThrownEnderpearl pearl : new ArrayList<>(old.getEnderPearls())) {
            old.deregisterEnderPearl(pearl);
            pearl.setOwner(null);
            if (pearl.level() instanceof ServerLevel level)
                affected.computeIfAbsent(level, ignored -> new HashSet<>()).add(pearl.chunkPosition());
            pearl.discard();
        }
        old.getEnderPearls().clear();
        // Tickets are shared by type and chunk, not by owner. Preserve another pearl's ticket.
        affected.forEach((level, chunks) -> {
            for (Entity entity : level.getAllEntities())
                if (entity instanceof ThrownEnderpearl && !entity.isRemoved()) chunks.remove(entity.chunkPosition());
            chunks.forEach(chunk -> level.getChunkSource().removeTicketWithRadius(TicketType.ENDER_PEARL, chunk, 2));
        });
    }

    static void afterTransition(ServerPlayer old) {
        for (ServerLevel level : old.level().getServer().getAllLevels())
            for (Entity entity : level.getAllEntities()) {
                if (entity instanceof Mob mob && mob.getTarget() == old) mob.setTarget(null);
                clean(entity);
            }
    }
}
