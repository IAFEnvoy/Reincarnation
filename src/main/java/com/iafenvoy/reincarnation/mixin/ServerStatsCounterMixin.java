package com.iafenvoy.reincarnation.mixin;

import com.google.gson.JsonElement;
import com.iafenvoy.reincarnation.lifecycle.ProgressAccess;
import net.minecraft.stats.ServerStatsCounter;
import net.minecraft.stats.Stat;
import net.minecraft.stats.StatsCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.HashMap;
import java.util.Map;

@Mixin(ServerStatsCounter.class)
public abstract class ServerStatsCounterMixin extends StatsCounter implements ProgressAccess {
    @Shadow
    protected abstract JsonElement toJson();

    @Override
    public String reincarnation$json() {
        return this.toJson().toString();
    }

    @Override
    public Map<Stat<?>, Integer> reincarnation$values() {
        return new HashMap<>(this.stats);
    }
}
