/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.neoforge.mod;

import io.izzel.arclight.common.optimization.general.servercore.EventBusStats;
import net.minecraft.server.MinecraftServer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Attaches {@link EventBusStats} to the NeoForge game event bus.
 *
 * <p>The bus indexes listener lists in an identity map keyed by the bus-layer
 * {@code Class} objects, and its supertype chain is only built through
 * <em>non-abstract</em> parent classes. Registering on the abstract
 * {@code Event} base class therefore never receives any subclass event, and
 * the mixin path is unavailable because {@code EventBus} loads before any
 * arclight mixin config exists. Instead this installer registers a HIGHEST
 * start listener + LOWEST end listener directly on every concrete event-class
 * {@code ListenerList} already known to the game bus, then refreshes once per
 * server tick to cover event classes seen for the first time later. The
 * registration itself uses the bus's own class-loader-resolved types so the
 * identity map and method signatures always match.</p>
 */
public final class EventBusTelemetry {

    private static final Logger LOGGER = LogManager.getLogger("Arclight");
    private static final AtomicBoolean ATTACHED = new AtomicBoolean();
    private static volatile Context CONTEXT;

    private EventBusTelemetry() {
    }

    public static void attach() {
        if (!io.izzel.arclight.common.compat.prts.PRTSFeaturesConfig.eventBusTelemetryEnabled) {
            return;
        }
        if (!ATTACHED.compareAndSet(false, true)) {
            return;
        }
        try {
            CONTEXT = Context.create();
            EventBusStats.setEventBusRefreshCallback(EventBusTelemetry::refresh);
            refresh(0);
            LOGGER.info("[eventbus] telemetry attached: busId={} knownClasses={}",
                    CONTEXT.busId, CONTEXT.readMap().size());
        } catch (Throwable t) {
            LOGGER.error("[eventbus] failed to attach telemetry; continuing without EventBus timing", t);
        }
    }

    /** One scan per server tick; newly observed event classes are instrumented here. */
    public static void refresh(int serverTick) {
        Context context = CONTEXT;
        if (context == null) {
            return;
        }
        try {
            Map<?, ?> map = context.readMap();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                Object key = entry.getKey();
                if (!(key instanceof Class<?>)) {
                    continue;
                }
                if (!context.registered.add(key)) {
                    continue;
                }
                context.register.invoke(entry.getValue(), context.highest, context.start);
                context.register.invoke(entry.getValue(), context.lowest, context.end);
            }
        } catch (Throwable t) {
            LOGGER.error("[eventbus] failed to refresh telemetry; continuing with previously registered classes", t);
        }
    }

    private static final class Context {

        final int busId;
        final Method getReadMap;
        final Method register;
        final Object highest;
        final Object lowest;
        final Object start;
        final Object end;
        final Set<Object> registered = Collections.newSetFromMap(new IdentityHashMap<>());

        private Context(int busId, Method getReadMap, Method register,
                        Object highest, Object lowest, Object start, Object end) {
            this.busId = busId;
            this.getReadMap = getReadMap;
            this.register = register;
            this.highest = highest;
            this.lowest = lowest;
            this.start = start;
            this.end = end;
        }

        Map<?, ?> readMap() throws Exception {
            return (Map<?, ?>) getReadMap.invoke(lock);
        }

        private Object lock;

        static Context create() throws Exception {
            ClassLoader gameLoader = MinecraftServer.class.getClassLoader();
            Class<?> neoForgeClass = Class.forName("net.neoforged.neoforge.common.NeoForge", false, gameLoader);
            Field eventBusField = neoForgeClass.getField("EVENT_BUS");
            Object bus = eventBusField.get(null);

            ClassLoader busLoader = bus.getClass().getClassLoader();
            Class<?> eventBusClass = Class.forName("net.neoforged.bus.EventBus", false, busLoader);
            Class<?> listenerListClass = Class.forName("net.neoforged.bus.ListenerList", false, busLoader);
            Class<?> eventListenerClass = Class.forName("net.neoforged.bus.api.EventListener", false, busLoader);
            Class<?> eventPriorityClass = Class.forName("net.neoforged.bus.api.EventPriority", false, busLoader);
            Class<?> consumerHandlerClass = Class.forName("net.neoforged.bus.ConsumerEventHandler", false, busLoader);

            Field listenerListsField = eventBusClass.getDeclaredField("listenerLists");
            listenerListsField.setAccessible(true);
            Object lock = listenerListsField.get(bus);

            Method getReadMap = lock.getClass().getDeclaredMethod("getReadMap");
            getReadMap.setAccessible(true);

            Constructor<?> consumerHandlerConstructor = consumerHandlerClass.getConstructor(java.util.function.Consumer.class);
            Object start = consumerHandlerConstructor.newInstance(
                    (java.util.function.Consumer<Object>) EventBusStats::onDispatchStart);
            Object end = consumerHandlerConstructor.newInstance(
                    (java.util.function.Consumer<Object>) EventBusStats::onDispatchEnd);

            Method register = listenerListClass.getMethod("register", eventPriorityClass, eventListenerClass);
            @SuppressWarnings({"unchecked", "rawtypes"})
            Object highest = Enum.valueOf((Class<? extends Enum>) eventPriorityClass.asSubclass(Enum.class), "HIGHEST");
            @SuppressWarnings({"unchecked", "rawtypes"})
            Object lowest = Enum.valueOf((Class<? extends Enum>) eventPriorityClass.asSubclass(Enum.class), "LOWEST");

            Context context = new Context(System.identityHashCode(bus), getReadMap, register,
                    highest, lowest, start, end);
            context.lock = lock;
            return context;
        }
    }
}
