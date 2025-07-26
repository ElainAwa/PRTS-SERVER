package io.izzel.arclight.common.mixin.optimization.general;

import io.izzel.arclight.i18n.ArclightConfig;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class AsyncEventSystem {
    private static final Logger LOGGER = LogManager.getLogger("Luminara-AsyncEvent");
    private static final AtomicBoolean initialized = new AtomicBoolean(false);
    private static ExecutorService asyncExecutor;
    private static ScheduledExecutorService scheduledExecutor;
    private static final Map<Class<? extends Event>, EventTypeInfo> eventTypeInfos = new ConcurrentHashMap<>();
    private static final AtomicLong totalAsyncTasks = new AtomicLong(0);

    public static void initialize() {
        if (initialized.getAndSet(true)) return;

        var config = ArclightConfig.spec().getOptimization().getAsyncSystem();
        if (!config.isEnabled()) return;

        int threads = Math.max(1, config.getMaxThreads());
        asyncExecutor = Executors.newWorkStealingPool(threads);
        scheduledExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Luminara-MPEM-AsyncEvent-Scheduler");
            t.setDaemon(true);
            return t;
        });


    }

    public static void shutdown() {
        if (!initialized.get()) return;

        try {
            if (asyncExecutor != null) {
                asyncExecutor.shutdown();
                if (!asyncExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    asyncExecutor.shutdownNow();
                }
            }

            if (scheduledExecutor != null) {
                scheduledExecutor.shutdown();
                if (!scheduledExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduledExecutor.shutdownNow();
                }
            }


        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.warn("Interrupted during shutdown", e);
        }
    }

    public static boolean shouldHandleAsync(Class<? extends Event> eventType) {
        var config = ArclightConfig.spec().getOptimization().getAsyncSystem();
        if (!config.isEnabled()) return false;

        EventTypeInfo info = eventTypeInfos.get(eventType);
        if (info != null) {
            if (info.isClientEvent && FMLEnvironment.dist.isDedicatedServer()) {
                return false;
            }
            return info.async && info.healthy;
        }

        // Check blacklists
        String className = eventType.getName();
        for (String pattern : config.getEventClassBlacklist()) {
            if (matchesPattern(className, pattern)) {
                return false;
            }
        }

        return eventType.getSimpleName().contains("Async");
    }

    public static CompletableFuture<Void> executeAsync(Class<? extends Event> eventType, Runnable task) {
        var config = ArclightConfig.spec().getOptimization().getAsyncSystem();
        if (!config.isEnabled() || !initialized.get()) {
            task.run();
            return CompletableFuture.completedFuture(null);
        }

        totalAsyncTasks.incrementAndGet();

        if (eventType.getName().contains("Client") && FMLEnvironment.dist.isDedicatedServer()) {
            task.run();
            return CompletableFuture.completedFuture(null);
        }

        EventTypeInfo info = eventTypeInfos.computeIfAbsent(
                eventType,
                k -> new EventTypeInfo(shouldHandleAsync(eventType))
        );

        if (!info.async || !info.healthy) {
            task.run();
            return CompletableFuture.completedFuture(null);
        }

        return CompletableFuture.runAsync(() -> {
            try {
                task.run();
                info.successCount.incrementAndGet();
            } catch (Exception e) {
                info.failedCount.incrementAndGet();

                if (config.isDisableOnError() && info.failedCount.get() > 3) {
                    info.healthy = false;
                    LOGGER.warn("Disabling async for event type due to errors: {}", eventType.getName());
                }

                LOGGER.error("Error in async event handler for {}", eventType.getName(), e);
                throw new RuntimeException(e);
            }
        }, asyncExecutor).orTimeout(config.getTimeoutSeconds(), TimeUnit.SECONDS);
    }

    public static void registerAsyncEvent(Class<? extends Event> eventType) {
        var config = ArclightConfig.spec().getOptimization().getAsyncSystem();
        if (!config.isEnabled()) return;

        eventTypeInfos.compute(eventType, (k, v) -> {
            if (v == null) {
                EventTypeInfo info = new EventTypeInfo(true);
                info.isClientEvent = isClientOnlyEvent(eventType);
                return info;
            }
            v.async = true;
            v.healthy = true;
            v.failedCount.set(0);
            v.isClientEvent = isClientOnlyEvent(eventType);
            return v;
        });

        LOGGER.debug("Registered async event: {}", eventType.getName());
    }

    public static void registerSyncEvent(Class<? extends Event> eventType) {
        var config = ArclightConfig.spec().getOptimization().getAsyncSystem();
        if (!config.isEnabled()) return;

        eventTypeInfos.compute(eventType, (k, v) -> {
            if (v == null) {
                EventTypeInfo info = new EventTypeInfo(false);
                info.isClientEvent = isClientOnlyEvent(eventType);
                return info;
            }
            v.async = false;
            v.isClientEvent = isClientOnlyEvent(eventType);
            return v;
        });

        LOGGER.debug("Registered sync event: {}", eventType.getName());
    }

    private static boolean isClientOnlyEvent(Class<? extends Event> eventType) {
        return eventType.getName().contains("Client") ||
                eventType.getName().contains("Render") ||
                eventType.getName().contains("Input");
    }

    private static boolean matchesPattern(String text, String pattern) {
        if (pattern.contains("*")) {
            String regex = pattern.replace("*", ".*");
            return text.matches(regex);
        }
        return text.equals(pattern);
    }

    public static long getTotalAsyncTasks() {
        return totalAsyncTasks.get();
    }

    private static class EventTypeInfo {
        volatile boolean async;
        volatile boolean healthy = true;
        volatile boolean isClientEvent = false;
        final AtomicInteger failedCount = new AtomicInteger(0);
        final AtomicInteger successCount = new AtomicInteger(0);

        EventTypeInfo(boolean async) {
            this.async = async;
        }
    }
}
