package com.iafenvoy.reincarnation.reference;

import com.iafenvoy.reincarnation.api.LifeReferences;
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import net.minecraft.world.entity.EntityReference;
import net.minecraft.world.level.entity.UniquelyIdentifyable;

import java.util.OptionalLong;

public final class ReferenceCodecs {
    private ReferenceCodecs() {
    }

    public static <E extends UniquelyIdentifyable> Codec<EntityReference<E>> wrap(Codec<EntityReference<E>> vanilla) {
        return new Codec<>() {
            @Override
            public <T> DataResult<T> encode(EntityReference<E> reference, DynamicOps<T> ops, T prefix) {
                return vanilla.encode(reference, ops, prefix).flatMap(encoded -> {
                    OptionalLong generation = LifeReferences.generation(reference);
                    if (generation.isEmpty()) return DataResult.success(encoded);
                    if (!(encoded instanceof StampedTag node))
                        return DataResult.error(() -> "Stamped EntityReference requires the NBT persistence backend");
                    node.reincarnation$stamp(new ReferenceStamp(reference.getUUID(), generation.getAsLong()));
                    return DataResult.success(encoded);
                });
            }

            @Override
            public <T> DataResult<Pair<EntityReference<E>, T>> decode(DynamicOps<T> ops, T input) {
                return vanilla.decode(ops, input).map(pair -> {
                    if (input instanceof StampedTag node) {
                        ReferenceStamp stamp = node.reincarnation$stamp();
                        if (stamp != null) {
                            if (!stamp.target().equals(pair.getFirst().getUUID()))
                                throw new ReferenceMetadataException("UUID node changed after reference metadata restoration");
                            ((GenerationReference) (Object) pair.getFirst()).reincarnation$generation(stamp.generation());
                        }
                    }
                    return pair;
                });
            }
        };
    }
}
