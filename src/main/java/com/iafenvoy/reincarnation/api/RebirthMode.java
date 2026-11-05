package com.iafenvoy.reincarnation.api;

import com.mojang.serialization.Codec;
import net.minecraft.util.StringRepresentable;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;

public enum RebirthMode implements StringRepresentable {
    CONFIGURED, NEW_LIFE, RESET;

    public static final Codec<RebirthMode> CODEC = StringRepresentable.fromEnum(RebirthMode::values);

    @Override
    public @NotNull String getSerializedName() {
        return this.name().toLowerCase(Locale.ROOT);
    }
}
