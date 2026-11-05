package com.iafenvoy.reincarnation.api;

import com.iafenvoy.reincarnation.reference.GenerationReference;
import com.iafenvoy.reincarnation.storage.Generations;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityReference;
import net.minecraft.world.level.entity.UniquelyIdentifyable;

import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

public final class LifeReferences {
    private LifeReferences() {
    }

    public static OptionalLong generation(EntityReference<?> reference) {
        long generation = ((GenerationReference) (Object) reference).reincarnation$generation();
        return generation < 0 ? OptionalLong.empty() : OptionalLong.of(generation);
    }

    /**
     * New player relationship, including when the target player is offline.
     */
    public static <T extends UniquelyIdentifyable> EntityReference<T> bind(ServerLevel level, UUID playerId) {
        return restore(playerId, Generations.current(level.getServer(), playerId));
    }

    /**
     * Restore an explicit historical generation. Never use current generation while decoding old data.
     */
    public static <T extends UniquelyIdentifyable> EntityReference<T> restore(UUID playerId, long generation) {
        EntityReference<T> reference = EntityReference.of(playerId);
        ((GenerationReference) (Object) reference).reincarnation$generation(generation);
        return reference;
    }

    public static boolean isCurrent(EntityReference<?> reference, UniquelyIdentifyable target) {
        return !(target instanceof ServerPlayer player) || generation(reference).orElse(0L) == Generations.current(player.level().getServer(), player.getUUID());
    }

    /**
     * UUID equality intentionally stays unchanged; this comparison additionally includes reference metadata.
     */
    public static boolean sameLife(EntityReference<?> first, EntityReference<?> second) {
        return first.getUUID().equals(second.getUUID()) && generation(first).equals(generation(second));
    }

    public static boolean metadataChanged(Object previous, Object replacement) {
        if (previous instanceof Optional<?> left && replacement instanceof Optional<?> right)
            return metadataChanged(left.orElse(null), right.orElse(null));
        return previous instanceof EntityReference<?> left && replacement instanceof EntityReference<?> right
                && left.getUUID().equals(right.getUUID()) && !generation(left).equals(generation(right));
    }
}
