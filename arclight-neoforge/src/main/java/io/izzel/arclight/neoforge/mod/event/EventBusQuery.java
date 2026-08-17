package io.izzel.arclight.neoforge.mod.event;

import java.lang.reflect.Method;
import net.neoforged.bus.EventBus;
import net.neoforged.bus.ListenerList;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Read-only "does anyone listen to this Forge event" query (plan A-06 common
 * base). Zero side effects: reads the bus's LockHelper cache via the private
 * {@code EventBus.getListenerList(Class)} (reflection, method handle cached at
 * class init — the bus jar lives in the MC-BOOTSTRAP module layer where mixins
 * cannot inject, bytecode-verified) and {@code ListenerList.getListeners()} is an
 * O(1) AtomicReference read (builds the listener array once after a registration,
 * which is low-frequency). Thread-safe and lock-free on the hot path, so it can
 * run on region/dimension workers.
 */
public final class EventBusQuery {

    private static final Method GET_LISTENER_LIST;

    static {
        Method method = null;
        try {
            method = EventBus.class.getDeclaredMethod("getListenerList", Class.class);
            method.setAccessible(true);
        } catch (NoSuchMethodException e) {
            // no-op: query falls back to "has listeners" (never short-circuit)
        }
        GET_LISTENER_LIST = method;
    }

    private EventBusQuery() {
    }

    /** True if the main bus has at least one listener for the given event class. */
    public static boolean hasListeners(Class<? extends Event> eventClass) {
        IEventBus bus = NeoForge.EVENT_BUS;
        if (bus instanceof EventBus impl && GET_LISTENER_LIST != null) {
            try {
                ListenerList list = (ListenerList) GET_LISTENER_LIST.invoke(impl, eventClass);
                return list.getListeners().length > 0;
            } catch (ReflectiveOperationException e) {
                // conservative: never short-circuit on a query failure
                return true;
            }
        }
        // Unknown bus implementation: be conservative and never short-circuit.
        return true;
    }
}
