package com.iafenvoy.reincarnation.api;

import net.minecraft.network.chat.Component;

/**
 * A lifecycle failure whose message can be localized by the receiving client.
 */
public final class ReincarnationException extends IllegalStateException {
    private final Component component;

    public ReincarnationException(String translationKey, Object... arguments) {
        super(translationKey);
        this.component = Component.translatable(translationKey, arguments);
    }

    public Component component() {
        return this.component;
    }
}
