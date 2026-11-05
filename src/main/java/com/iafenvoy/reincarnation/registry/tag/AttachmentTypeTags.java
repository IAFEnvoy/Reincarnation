package com.iafenvoy.reincarnation.registry.tag;

import com.iafenvoy.reincarnation.Reincarnation;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

public final class AttachmentTypeTags {
    public static final TagKey<AttachmentType<?>> KEEP_ON_REBIRTH = TagKey.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, Identifier.fromNamespaceAndPath(Reincarnation.MOD_ID, "keep_on_rebirth"));
}
