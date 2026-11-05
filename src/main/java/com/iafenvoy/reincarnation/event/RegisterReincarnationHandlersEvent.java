package com.iafenvoy.reincarnation.event;

import com.iafenvoy.reincarnation.api.ReincarnationHandler;
import net.neoforged.bus.api.Event;
import net.neoforged.fml.event.IModBusEvent;

import java.util.function.Consumer;

public final class RegisterReincarnationHandlersEvent extends Event implements IModBusEvent {
    private final Consumer<ReincarnationHandler> registry;

    public RegisterReincarnationHandlersEvent(Consumer<ReincarnationHandler> registry) {
        this.registry = registry;
    }

    public void register(ReincarnationHandler handler) {
        this.registry.accept(handler);
    }
}
