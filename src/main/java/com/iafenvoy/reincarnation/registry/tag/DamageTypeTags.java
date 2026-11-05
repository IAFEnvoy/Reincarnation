package com.iafenvoy.reincarnation.registry.tag;

import com.iafenvoy.reincarnation.Reincarnation;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.damagesource.DamageType;

public final class DamageTypeTags {
    public static final TagKey<DamageType> REINCARNATE_ON_DEATH = TagKey.create(Registries.DAMAGE_TYPE,
            Identifier.fromNamespaceAndPath(Reincarnation.MOD_ID, "reincarnate_on_death"));
}
