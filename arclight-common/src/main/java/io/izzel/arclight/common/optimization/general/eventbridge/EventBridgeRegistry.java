/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.optimization.general.eventbridge;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * On-demand registration gate for the Forge event bridge.
 *
 * <p>Each bridge dispatcher (registered by the platform layer through
 * {@link #registerGate(Class[])}) declares a "gate set" of Bukkit event classes it
 * must stay alive for. The gate is open when <em>any</em> plugin listens to any of
 * those events; when a gate flips, {@link PlatformBridge#onGateChanged} is invoked
 * so the platform layer can register/unregister the corresponding Forge dispatcher
 * on the bus. With no plugins listening, the dispatchers are absent from the bus and
 * the whole bridge overhead (craft-block/event construction + empty Bukkit dispatch)
 * is zero.</p>
 *
 * <p>Hooks: {@code HandlerListMixin_EventBridge} notifies us on every register /
 * unregister path (register / registerAll / unregister x3 / unregisterAll x3 — the
 * complete set, including plugins calling {@code HandlerList.unregister*} directly).
 * Evaluation reads {@code HandlerList.getRegisteredListeners()} which is an O(1)
 * volatile array read (bake-on-null), so a re-evaluation after a plugin enable /
 * disable / reload costs a few hundred ns.</p>
 *
 * <p>Thread discipline: Bukkit contract says plugin event registration happens on the
 * main thread only, so all gate transitions run on the main thread; the dispatchers
 * may read the gate state from worker threads, which is safe because the reads are
 * plain volatile array-length reads.</p>
 */
public final class EventBridgeRegistry {

    /** Platform-side bridge lifecycle (implemented in arclight-neoforge). */
    public interface PlatformBridge {
        /** Called when a gate flips; idempotent (bridge tracks its own state). */
        void onGateChanged(int bindingIndex, boolean shouldRegister);
    }

    /** Master switch, driven by {@code event-bridge.on-demand-registration.enabled}. */
    private static volatile boolean active = true;
    private static volatile PlatformBridge bridge;

    /** One gate = the resolved static HandlerLists of the gate event classes. */
    private static final List<List<HandlerList>> GATES = new ArrayList<>();
    private static final List<Boolean> LAST_STATE = new ArrayList<>();

    /** Class → static HandlerList resolution cache (direct call sites use this). */
    private static final ConcurrentMap<Class<? extends Event>, HandlerList> HANDLER_LISTS = new ConcurrentHashMap<>();

    private EventBridgeRegistry() {
    }

    /** Platform mod-init hook: install the bridge lifecycle callback. */
    public static void setBridge(PlatformBridge bridge) {
        EventBridgeRegistry.bridge = bridge;
    }

    /**
     * Apply the master switch (called from PRTSFeaturesConfig.init). {@code false}
     * restores the old always-registered behavior; {@code true} converges to the
     * current gate state.
     */
    public static void setActive(boolean enabled) {
        active = enabled;
        if (bridge == null) {
            return;
        }
        if (!enabled) {
            // old behavior: every dispatcher stays on the bus
            for (int i = 0; i < GATES.size(); i++) {
                if (!LAST_STATE.get(i)) {
                    LAST_STATE.set(i, true);
                    bridge.onGateChanged(i, true);
                }
            }
        } else {
            resync();
        }
    }

    /**
     * Register one bridge dispatcher's gate set; returns the binding index used in
     * {@link PlatformBridge#onGateChanged}. Must be called before the first plugin
     * enable (platform mod init).
     */
    public static synchronized int registerGate(Class<? extends Event>... gateClasses) {
        int index = GATES.size();
        List<HandlerList> gate = new ArrayList<>(gateClasses.length);
        for (Class<? extends Event> clazz : gateClasses) {
            if (clazz != null) {
                gate.add(resolveHandlerList(clazz));
            }
        }
        GATES.add(gate);
        LAST_STATE.add(anyListeners(gate));
        return index;
    }

    /**
     * Called by {@code HandlerListMixin_EventBridge} after any register/unregister
     * mutation. Re-evaluates every gate and flips dispatchers on the bus when a gate
     * changes (0→1 register, 1→0 unregister).
     */
    public static void onHandlerListChanged() {
        if (!active || bridge == null) {
            return;
        }
        resync();
    }

    private static void resync() {
        for (int i = 0; i < GATES.size(); i++) {
            boolean now = anyListeners(GATES.get(i));
            if (now != LAST_STATE.get(i)) {
                LAST_STATE.set(i, now);
                bridge.onGateChanged(i, now);
            }
        }
    }

    /** O(1) "any plugin listens to this event" check (volatile array read). */
    public static boolean hasListeners(Class<? extends Event> clazz) {
        return resolveHandlerList(clazz).getRegisteredListeners().length > 0;
    }

    private static boolean anyListeners(List<HandlerList> gate) {
        for (HandlerList handlers : gate) {
            if (handlers.getRegisteredListeners().length > 0) {
                return true;
            }
        }
        return false;
    }

    private static HandlerList resolveHandlerList(Class<? extends Event> clazz) {
        HandlerList cached = HANDLER_LISTS.get(clazz);
        if (cached != null) {
            return cached;
        }
        try {
            Method method = clazz.getMethod("getHandlerList");
            HandlerList resolved = (HandlerList) method.invoke(null);
            HANDLER_LISTS.putIfAbsent(clazz, resolved);
            return resolved;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot resolve handler list for " + clazz.getName(), e);
        }
    }
}
