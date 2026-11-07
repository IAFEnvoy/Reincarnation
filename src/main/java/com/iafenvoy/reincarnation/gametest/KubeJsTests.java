package com.iafenvoy.reincarnation.gametest;

import com.iafenvoy.reincarnation.api.RebirthMode;
import com.iafenvoy.reincarnation.compat.kubejs.ReincarnationKubeJsPlugin;
import com.iafenvoy.reincarnation.config.ReincarnationServerConfig;
import com.iafenvoy.reincarnation.lifespan.LifespanService;
import com.iafenvoy.reincarnation.registry.ReincarnationAttachments;
import com.iafenvoy.reincarnation.storage.Generations;
import dev.latvian.mods.kubejs.core.ScriptManagerHolderKJS;
import dev.latvian.mods.kubejs.event.EventGroup;
import dev.latvian.mods.kubejs.event.EventHandler;
import dev.latvian.mods.kubejs.event.IEventHandler;
import dev.latvian.mods.kubejs.plugin.KubeJSPlugins;
import dev.latvian.mods.kubejs.script.KubeJSContext;
import dev.latvian.mods.kubejs.script.ScriptType;
import dev.latvian.mods.kubejs.server.ServerScriptManager;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Loaded only when KubeJS is present; uses its discovered plugin and server script context.
 */
final class KubeJsTests {
    static Map<String, Consumer<GameTestHelper>> tests() {
        return Map.of("kubejs_lifespan", KubeJsTests::bindings,
                "kubejs_lifespan_events", KubeJsTests::events);
    }

    private static KubeJSContext context(GameTestHelper h) {
        h.assertValueEqual(KubeJSPlugins.getAll().stream().filter(ReincarnationKubeJsPlugin.class::isInstance).count(), 1L,
                "KubeJS discovers exactly one Reincarnation plugin without MiXianTu");
        ServerScriptManager manager = ((ScriptManagerHolderKJS) h.getLevel().getServer().getResourceManager()).kjs$getScriptManager();
        return (KubeJSContext) manager.contextFactory.enter();
    }

    private static void script(GameTestHelper h, KubeJSContext cx, String code) {
        Object result = cx.evaluateString(cx.topLevelScope, code, "reincarnation-gametest", 1, null);
        h.assertValueEqual(result, Boolean.TRUE, "the script's lifespan assertions succeed");
    }

    private static void bindings(GameTestHelper h) {
        ReincarnationServerConfig config = ReincarnationServerConfig.INSTANCE;
        String original = config.serialize();
        ServerPlayer player = LifecycleTests.connect(h);
        try {
            config.lifespan.enabled.setValue(false);
            config.lifespan.playerRebirthMode.setValue(RebirthMode.NEW_LIFE);
            KubeJSContext cx = context(h);
            cx.addToScope(cx.topLevelScope, "probePlayer", player);
            script(h, cx, """
                    if (typeof MxtLifespan !== 'undefined') throw new Error('MXT binding leaked');
                    if (ReincarnationLifespan.remaining(probePlayer) !== -1) throw new Error('unexpected ledger');
                    if (ReincarnationLifespan.total(probePlayer) !== -1) throw new Error('unexpected total');
                    if (!ReincarnationLifespan.set(probePlayer, -1).getFailure().isPresent()) throw new Error('negative set accepted');
                    if (ReincarnationLifespan.set(probePlayer, 100).getFailure().isPresent()) throw new Error('set failed');
                    ReincarnationLifespan.add(probePlayer, 40);
                    ReincarnationLifespan.add(probePlayer, -10);
                    if (ReincarnationLifespan.remaining(probePlayer) !== 130 || ReincarnationLifespan.total(probePlayer) !== 140)
                        throw new Error('incorrect ledger arithmetic');
                    ReincarnationLifespan.unlimited(probePlayer);
                    if (ReincarnationLifespan.remaining(probePlayer) !== -1 || ReincarnationLifespan.total(probePlayer) !== 0)
                        throw new Error('unlimited failed');
                    ReincarnationLifespan.set(probePlayer, 80);
                    if (ReincarnationLifespan.reincarnate(probePlayer).getFailure().isPresent()) throw new Error('request failed');
                    if (!ReincarnationLifespan.reincarnate(probePlayer).getFailure().isPresent()) throw new Error('duplicate accepted');
                    true;
                    """);
            h.assertTrue(player.connection.player == player, "a script request queues rather than replacing the player during its callback");
            LifespanService.flush();
            h.assertTrue(player.connection.player != player && player.connection.player.isAlive(), "queued script rebirth replaces the player");
            h.assertValueEqual(Generations.current(h.getLevel().getServer(), player.getUUID()), 1L, "script rebirth advances one life");
            h.succeed();
        } finally {
            LifecycleTests.remove(player.connection.player);
            config.deserialize(original);
        }
    }

    private static void events(GameTestHelper h) {
        ReincarnationServerConfig config = ReincarnationServerConfig.INSTANCE;
        String original = config.serialize();
        List<EventGroup> groups = new ArrayList<>();
        KubeJSPlugins.getAll().stream().filter(ReincarnationKubeJsPlugin.class::isInstance)
                .forEach(plugin -> plugin.registerEvents(groups::add));
        EventGroup group = groups.getFirst();
        Map<EventHandler, List<IEventHandler>> previous = new LinkedHashMap<>();
        group.getHandlers().values().forEach(handler -> {
            List<IEventHandler> listeners = new ArrayList<>();
            handler.forEachListener(ScriptType.SERVER, entry -> listeners.add(entry.handler));
            previous.put(handler, listeners);
            handler.clear(ScriptType.SERVER);
        });
        ServerPlayer player = LifecycleTests.connect(h);
        ServerScriptManager manager = ((ScriptManagerHolderKJS) h.getLevel().getServer().getResourceManager()).kjs$getScriptManager();
        boolean listening = manager.canListenEvents;
        try {
            config.lifespan.enabled.setValue(true);
            config.lifespan.ageCreative.setValue(true);
            config.lifespan.agePerSettle.setValue(20);
            config.lifespan.onExpire.setValue(ReincarnationServerConfig.LifespanOutcome.NONE);
            config.lifespan.playerRebirthMode.setValue(RebirthMode.RESET);
            config.lifespan.baseLifespan.setValue(100L);
            KubeJSContext cx = context(h);
            cx.addToScope(cx.topLevelScope, "probePlayer", player);
            manager.canListenEvents = true;
            script(h, cx, """
                    var endPre = 0, endPost = 0, rebirthPre = 0, rebirthPost = 0, rebirthFailed = 0;
                    var cancelExpiry = true, cancelRebirth = false;
                    ReincarnationEvents.lifespanEnd(event => {
                        if (event.getPhase() === 'Pre') {
                            endPre++;
                            if (!event.isCancellable() || event.getType() !== 'lifespanEnd') throw new Error('wrong expiry wrapper');
                            if (cancelExpiry) {
                                ReincarnationLifespan.set(probePlayer, 50);
                                event.cancel();
                            }
                        } else if (event.getPhase() === 'Post') endPost++;
                    });
                    ReincarnationEvents.lifespanRebirth(event => {
                        if (event.getPhase() === 'Pre') {
                            rebirthPre++;
                            if (cancelRebirth) event.cancel();
                        } else if (event.getPhase() === 'Post') rebirthPost++;
                        else if (event.getPhase() === 'Failed') rebirthFailed++;
                    });
                    ReincarnationLifespan.set(probePlayer, 1);
                    true;
                    """);
            manager.canListenEvents = listening;
            LifespanService.settle(player);
            h.assertValueEqual(LifespanService.remaining(player), 50L, "script cancellation and renewal prevent expiry");
            script(h, cx, """
                    if (endPre !== 1 || endPost !== 0) throw new Error('cancelled expiry published Post');
                    cancelExpiry = false;
                    ReincarnationLifespan.set(probePlayer, 1);
                    true;
                    """);
            LifespanService.settle(player);
            script(h, cx, """
                    if (endPre !== 2 || endPost !== 1) throw new Error('expiry stages were lost or duplicated');
                    ReincarnationLifespan.reincarnate(probePlayer);
                    true;
                    """);
            LifespanService.flush();
            h.assertValueEqual(LifespanService.remaining(player), 100L, "configured in-place rebirth restores the baseline");
            script(h, cx, """
                    if (rebirthPre !== 1 || rebirthPost !== 1 || rebirthFailed !== 0) throw new Error('rebirth stages incorrect');
                    cancelRebirth = true;
                    ReincarnationLifespan.reincarnate(probePlayer);
                    true;
                    """);
            LifespanService.flush();
            script(h, cx, """
                    if (rebirthPre !== 2 || rebirthPost !== 1 || rebirthFailed !== 1) throw new Error('cancelled rebirth stages incorrect');
                    true;
                    """);
            h.assertTrue(player.connection.player == player && player.hasData(ReincarnationAttachments.LIFESPAN),
                    "cancelled rebirth keeps the entity and ledger");
            h.succeed();
        } finally {
            manager.canListenEvents = listening;
            previous.forEach((handler, listeners) -> {
                handler.clear(ScriptType.SERVER);
                listeners.forEach(listener -> handler.listenJava(ScriptType.SERVER, null, listener));
            });
            LifecycleTests.remove(player.connection.player);
            config.deserialize(original);
        }
    }
}
