package com.iafenvoy.reincarnation.compat.kubejs;

import com.iafenvoy.reincarnation.Reincarnation;
import com.iafenvoy.reincarnation.compat.kubejs.binding.ReincarnationKubeJsLifespanBindings;
import com.iafenvoy.reincarnation.event.LifeSpanEndEvent;
import com.iafenvoy.reincarnation.event.LifeSpanRebirthEvent;
import dev.latvian.mods.kubejs.event.*;
import dev.latvian.mods.kubejs.plugin.KubeJSPlugin;
import dev.latvian.mods.kubejs.script.BindingRegistry;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.ICancellableEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Discovered only by KubeJS through {@code kubejs.plugins.txt}; the mod entry point never loads this class.
 */
public final class ReincarnationKubeJsPlugin implements KubeJSPlugin {
    private static final EventGroup EVENTS = EventGroup.of("ReincarnationEvents");
    private static final EventHandler LIFESPAN_END = EVENTS.server("lifespanEnd", () -> LifespanKubeEvent.class).hasResult();
    private static final EventHandler LIFESPAN_REBIRTH = EVENTS.server("lifespanRebirth", () -> LifespanKubeEvent.class).hasResult();

    @Override
    public void init() {
        forward(LifeSpanEndEvent.Pre.class, LIFESPAN_END);
        forward(LifeSpanEndEvent.Post.class, LIFESPAN_END);
        forward(LifeSpanRebirthEvent.Pre.class, LIFESPAN_REBIRTH);
        forward(LifeSpanRebirthEvent.Post.class, LIFESPAN_REBIRTH);
        forward(LifeSpanRebirthEvent.Failed.class, LIFESPAN_REBIRTH);
        Reincarnation.LOGGER.info("Reincarnation KubeJS bridge initialized");
    }

    private static <T extends Event> void forward(Class<T> type, EventHandler handler) {
        NeoForge.EVENT_BUS.addListener(type, event -> {
            if (!handler.hasListeners()) return;
            EventResult result = handler.post(new LifespanKubeEvent(handler.name, event));
            if (event instanceof ICancellableEvent cancellable) result.applyCancel(cancellable);
        });
    }

    @Override
    public void registerBindings(BindingRegistry registry) {
        registry.add("ReincarnationLifespan", new ReincarnationKubeJsLifespanBindings());
    }

    @Override
    public void registerEvents(EventGroupRegistry registry) {
        registry.register(EVENTS);
    }

    /**
     * Lifecycle wrapper; only Pre can cancel, and getEvent exposes the native event accessors.
     */
    public record LifespanKubeEvent(String type, Event event) implements KubeEvent {

        public String getPhase() {
            return this.event.getClass().getSimpleName();
        }

        public boolean isCancellable() {
            return this.event instanceof ICancellableEvent;
        }
    }
}
