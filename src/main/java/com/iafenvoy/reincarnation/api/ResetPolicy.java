package com.iafenvoy.reincarnation.api;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * A snapshot of gameplay choices; account facts such as bans and vault claims are always retained.
 */
public record ResetPolicy(boolean resetScores, boolean resetTeam, boolean resetUnknownModData, boolean keepGameMode) {
    public static final ResetPolicy DEFAULT = new ResetPolicy(true, false, false, true);
    public static final Codec<ResetPolicy> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.BOOL.fieldOf("scores").forGetter(ResetPolicy::resetScores),
            Codec.BOOL.fieldOf("team").forGetter(ResetPolicy::resetTeam),
            Codec.BOOL.fieldOf("unknown_mod_data").forGetter(ResetPolicy::resetUnknownModData),
            Codec.BOOL.fieldOf("keep_game_mode").forGetter(ResetPolicy::keepGameMode)
    ).apply(i, ResetPolicy::new));
}
