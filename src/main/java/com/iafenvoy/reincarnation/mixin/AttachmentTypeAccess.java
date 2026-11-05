package com.iafenvoy.reincarnation.mixin;

import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.attachment.IAttachmentCopyHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(AttachmentType.class)
public interface AttachmentTypeAccess {
    @Accessor("copyOnDeath")
    boolean reincarnation$copyOnDeath();

    @Accessor("copyHandler")
    IAttachmentCopyHandler<?> reincarnation$copyHandler();
}
