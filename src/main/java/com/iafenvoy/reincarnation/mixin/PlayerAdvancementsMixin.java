package com.iafenvoy.reincarnation.mixin;

import com.google.gson.JsonObject;
import com.iafenvoy.reincarnation.lifecycle.AdvancementAccess;
import com.mojang.serialization.JsonOps;
import net.minecraft.SharedConstants;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.server.PlayerAdvancements;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.Map;

@Mixin(PlayerAdvancements.class)
public abstract class PlayerAdvancementsMixin implements AdvancementAccess {
    @Shadow
    @Final
    private Map<AdvancementHolder, AdvancementProgress> progress;

    @Override
    public String reincarnation$json() {
        JsonObject json = new JsonObject();
        this.progress.forEach((holder, value) -> {
            if (value.hasProgress())
                json.add(holder.id().toString(), AdvancementProgress.CODEC.encodeStart(JsonOps.INSTANCE, value).getOrThrow());
        });
        json.addProperty("DataVersion", SharedConstants.getCurrentVersion().dataVersion().version());
        return json.toString();
    }

    @Override
    public int reincarnation$completed() {
        return (int) this.progress.values().stream().filter(AdvancementProgress::isDone).count();
    }
}
