package io.izzel.arclight.common.optimization.mpem;

import io.izzel.arclight.i18n.ArclightConfig;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

public class MpemThreadManager {
    private static final Logger LOGGER = LogManager.getLogger("Luminara-MPEM-ThreadManager");
    private static final AtomicInteger threadCounter = new AtomicInteger(0);

    // Shared thread pools for different types of tasks
    private static ExecutorService generalPool;
    private static ExecutorService ioPool;
    private static ScheduledExecutorService scheduledPool;

    public static void initialize() {
        var config = ArclightConfig.spec().getOptimization().getAsyncSystem();
        if (!config.isEnabled()) return;

        int maxThreads = config.getMaxThreads();
        int coreThreads = Math.max(1, maxThreads / 2);

        // General purpose thread pool
        generalPool = new ThreadPoolExecutor(
                coreThreads, maxThreads,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(1000),
                r -> createThread(r, "Luminara-MPEM-General"),
                new ThreadPoolExecutor.CallerRunsPolicy()
        );

        // IO-intensive operations pool
        ioPool = new ThreadPoolExecutor(
                2, Math.max(4, maxThreads / 4),
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(500),
                r -> createThread(r, "Luminara-MPEM-IO"),
                new ThreadPoolExecutor.CallerRunsPolicy()
        );

        // Scheduled tasks pool
        scheduledPool = Executors.newScheduledThreadPool(
                Math.max(1, maxThreads / 8),
                r -> createThread(r, "Luminara-MPEM-Scheduled")
        );


    }

    public static void shutdown() {


        shutdownPool(generalPool, "General");
        shutdownPool(ioPool, "IO");
        shutdownPool(scheduledPool, "Scheduled");


    }

    private static void shutdownPool(ExecutorService pool, String name) {
        if (pool == null) return;

        try {
            pool.shutdown();
            if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
                LOGGER.warn("{} pool did not terminate gracefully, forcing shutdown", name);
                pool.shutdownNow();
                if (!pool.awaitTermination(2, TimeUnit.SECONDS)) {
                    LOGGER.error("{} pool did not terminate after forced shutdown", name);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.warn("Interrupted while shutting down {} pool", name);
            pool.shutdownNow();
        }
    }

    private static Thread createThread(Runnable r, String prefix) {
        Thread t = new Thread(r, prefix + "-" + threadCounter.incrementAndGet());
        t.setDaemon(true);
        t.setUncaughtExceptionHandler((thread, ex) ->
                LOGGER.error("Uncaught exception in thread {}", thread.getName(), ex));
        return t;
    }

    // Public methods for submitting tasks
    public static CompletableFuture<Void> runAsync(Runnable task) {
        if (generalPool == null || generalPool.isShutdown()) {
            task.run();
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.runAsync(task, generalPool);
    }

    public static <T> CompletableFuture<T> supplyAsync(Supplier<T> supplier) {
        if (generalPool == null || generalPool.isShutdown()) {
            return CompletableFuture.completedFuture(supplier.get());
        }
        return CompletableFuture.supplyAsync(supplier, generalPool);
    }

    public static CompletableFuture<Void> runAsyncIO(Runnable task) {
        if (ioPool == null || ioPool.isShutdown()) {
            task.run();
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.runAsync(task, ioPool);
    }

    public static <T> CompletableFuture<T> supplyAsyncIO(Supplier<T> supplier) {
        if (ioPool == null || ioPool.isShutdown()) {
            return CompletableFuture.completedFuture(supplier.get());
        }
        return CompletableFuture.supplyAsync(supplier, ioPool);
    }

    public static ScheduledFuture<?> schedule(Runnable task, long delay, TimeUnit unit) {
        if (scheduledPool == null || scheduledPool.isShutdown()) {
            throw new IllegalStateException("Scheduled pool is not available");
        }
        return scheduledPool.schedule(task, delay, unit);
    }

    public static ScheduledFuture<?> scheduleAtFixedRate(Runnable task, long initialDelay, long period, TimeUnit unit) {
        if (scheduledPool == null || scheduledPool.isShutdown()) {
            throw new IllegalStateException("Scheduled pool is not available");
        }
        return scheduledPool.scheduleAtFixedRate(task, initialDelay, period, unit);
    }

    // Status methods
    public static boolean isHealthy() {
        return generalPool != null && !generalPool.isShutdown() &&
                ioPool != null && !ioPool.isShutdown() &&
                scheduledPool != null && !scheduledPool.isShutdown();
    }

    public static String getStatus() {
        if (!isHealthy()) {
            return "Thread Manager is not healthy";
        }

        StringBuilder status = new StringBuilder();
        status.append("Thread Manager Status:\n");

        if (generalPool instanceof ThreadPoolExecutor tpe) {
            status.append(String.format("General Pool: %d/%d threads, %d queued\n",
                    tpe.getActiveCount(), tpe.getPoolSize(), tpe.getQueue().size()));
        }

        if (ioPool instanceof ThreadPoolExecutor tpe) {
            status.append(String.format("IO Pool: %d/%d threads, %d queued\n",
                    tpe.getActiveCount(), tpe.getPoolSize(), tpe.getQueue().size()));
        }

        if (scheduledPool instanceof ThreadPoolExecutor tpe) {
            status.append(String.format("Scheduled Pool: %d/%d threads, %d queued",
                    tpe.getActiveCount(), tpe.getPoolSize(), tpe.getQueue().size()));
        }

        return status.toString();
    }
}
