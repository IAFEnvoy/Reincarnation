package com.iafenvoy.reincarnation.reference;

import org.jspecify.annotations.Nullable;

/**
 * Transient metadata only; never part of an NBT tag's value, equality or binary format.
 */
public interface StampedTag {
    @Nullable ReferenceStamp reincarnation$stamp();

    void reincarnation$stamp(@Nullable ReferenceStamp stamp);
}
