package com.iafenvoy.reincarnation.lifecycle;

import net.minecraft.stats.Stat;

import java.util.Map;

public interface ProgressAccess {
    String reincarnation$json();

    Map<Stat<?>, Integer> reincarnation$values();
}
