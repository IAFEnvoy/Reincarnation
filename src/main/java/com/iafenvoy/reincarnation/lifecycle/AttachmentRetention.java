package com.iafenvoy.reincarnation.lifecycle;

import com.iafenvoy.reincarnation.api.ResetPolicy;
import com.iafenvoy.reincarnation.mixin.AttachmentTypeAccess;
import com.iafenvoy.reincarnation.registry.tag.AttachmentTypeTags;
import com.iafenvoy.saturn.registry.SaturnAttachments;
import net.minecraft.core.Holder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.storage.TagValueInput;
import net.neoforged.neoforge.attachment.AttachmentHolder;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.attachment.IAttachmentCopyHandler;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.util.LinkedHashSet;
import java.util.Set;

public final class AttachmentRetention {
    private AttachmentRetention() {
    }

    static ListTag capture(ServerPlayer player, ResetPolicy policy) {
        ListTag kept = new ListTag();
        retained(player, policy).forEach(type -> kept.add(StringTag.valueOf(NeoForgeRegistries.ATTACHMENT_TYPES.getKey(type).toString())));
        return kept;
    }

    private static Set<AttachmentType<?>> retained(Entity entity, ResetPolicy policy) {
        Set<AttachmentType<?>> kept = new LinkedHashSet<>();
        if (policy.resetUnknownModData()) return kept;
        for (Holder.Reference<AttachmentType<?>> holder : NeoForgeRegistries.ATTACHMENT_TYPES.listElements().toList())
            if (entity.hasData(holder.value()) && copyOnDeath(holder.value())
                    && (holder.is(AttachmentTypeTags.KEEP_ON_REBIRTH)
                    || !policy.clearResources() && holder.value() == SaturnAttachments.RESOURCE_HOLDER.get()))
                kept.add(holder.value());
        return kept;
    }

    public static void reset(LivingEntity entity, ResetPolicy policy) {
        Set<AttachmentType<?>> kept = retained(entity, policy);
        for (AttachmentType<?> type : NeoForgeRegistries.ATTACHMENT_TYPES)
            if (entity.hasData(type) && !kept.contains(type)) entity.removeData(type);
    }

    static void restore(ServerPlayer player, LifeTransaction tx, CompoundTag archive, CompoundTag oldData) {
        if (tx.request().policy().resetUnknownModData()) return;
        CompoundTag saved = oldData.getCompound(AttachmentHolder.ATTACHMENTS_NBT_KEY).orElseGet(CompoundTag::new);
        CompoundTag retained = new CompoundTag();
        // Missing metadata in an older archive grants no implicit retention.
        for (Tag entry : archive.getList("kept_attachments").orElseGet(ListTag::new)) {
            String id = entry.asString().orElseThrow();
            AttachmentType<?> type = NeoForgeRegistries.ATTACHMENT_TYPES.getValue(Identifier.parse(id));
            if (type != null && copyOnDeath(type) && saved.contains(id))
                retained.put(id, saved.get(id).copy());
        }
        // Decode against the destination holder, then honor the same copy handler NeoForge uses.
        AttachmentHolder.AsField source = new AttachmentHolder.AsField(player);
        source.deserializeInternal(player.registryAccess(), TagValueInput.create(ProblemReporter.DISCARDING, player.registryAccess(), retained));
        for (String id : retained.keySet())
            copy(source, player, NeoForgeRegistries.ATTACHMENT_TYPES.getValue(Identifier.parse(id)));
    }

    private static boolean copyOnDeath(AttachmentType<?> type) {
        return ((AttachmentTypeAccess) (Object) type).reincarnation$copyOnDeath();
    }

    @SuppressWarnings("unchecked")
    private static <T> void copy(AttachmentHolder source, ServerPlayer destination, AttachmentType<T> type) {
        T value = source.getExistingDataOrNull(type);
        if (value == null) return;
        IAttachmentCopyHandler<T> handler = (IAttachmentCopyHandler<T>) ((AttachmentTypeAccess) (Object) type).reincarnation$copyHandler();
        T copied = handler.copy(value, destination, destination.registryAccess());
        if (copied != null) destination.setData(type, copied);
    }
}
