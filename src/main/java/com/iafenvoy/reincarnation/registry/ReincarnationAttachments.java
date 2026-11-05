package com.iafenvoy.reincarnation.registry;

import com.iafenvoy.reincarnation.Reincarnation;
import com.iafenvoy.reincarnation.lifespan.Lifespan;
import com.iafenvoy.reincarnation.lifespan.LifespanAttachment;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.damagesource.DamageTypes;

public final class ReincarnationAttachments {
    public static final DeferredRegister<AttachmentType<?>> REGISTRY = DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, Reincarnation.MOD_ID);
    public static final DeferredRegister<AttachmentType<?>> LEGACY_REGISTRY = DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, "mxt");
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<LifespanAttachment>> LIFESPAN =
            REGISTRY.register("lifespan", () -> AttachmentType.builder(() -> new LifespanAttachment()).serialize(LifespanAttachment.CODEC)
                    .sync(ByteBufCodecs.fromCodecWithRegistries(LifespanAttachment.CODEC.codec())).copyOnDeath().build());
    // Persist the cause on the corpse, without copying it to a replacement player.
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<Component>> DEATH_REASON =
            REGISTRY.register("death_reason", () -> AttachmentType.<Component>builder(Component::empty).serialize(ComponentSerialization.CODEC.fieldOf("reason")).build());
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<ResourceKey<DamageType>>> DEATH_DAMAGE_TYPE =
            REGISTRY.register("death_damage_type", () -> AttachmentType.builder(() -> DamageTypes.GENERIC)
                    .serialize(ResourceKey.codec(Registries.DAMAGE_TYPE).fieldOf("type")).build());
    // Keep the old id decodable until its data has been moved to the new attachment.
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<LifespanAttachment>> LEGACY_LIFESPAN =
            LEGACY_REGISTRY.register("lifespan", () -> AttachmentType.builder(() -> new LifespanAttachment())
                    .serialize(LifespanAttachment.CODEC).copyOnDeath().build());

    private ReincarnationAttachments() {
    }
}
