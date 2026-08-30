/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.optimization.general.servercore;

import io.izzel.arclight.common.compat.prts.PRTSFeaturesConfig;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.util.RandomSource;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/** 区块环境 tick 并行：收集 chunk 后在独立池执行，条带锁隔离相邻区块写。 */
public final class ChunkEnvParallelScheduler {

    private static final Logger LOGGER = LogManager.getLogger("Arclight");

    /** 子任务池(独立于 region/dimension 池,避免 await 时池线程被占导致的饿死)。 */
    private static volatile ThreadPoolExecutor pool;
    /** 收集窗口：tickChunks 只在维度 tick 线程跑（每维度一线程），ThreadLocal 天然隔离多维度。 */
    private static final ThreadLocal<PendingWindow> WINDOW = ThreadLocal.withInitial(PendingWindow::new);
    /** 固定条带锁：任务结束后无需回收，避免 per-chunk 锁表无界增长。 */
    private static final int LOCK_STRIPES = 1024;
    private static final ReentrantLock[] LOCKS = new ReentrantLock[LOCK_STRIPES];
    /** 3×3 锁半径常量:覆盖流体跨界写与邻居更新反应写。 */
    private static final int LOCK_RADIUS = 1;

    static {
        for (int i = 0; i < LOCK_STRIPES; i++) {
            LOCKS[i] = new ReentrantLock();
        }
    }

    /** 子任务线程本地随机源(per-chunk 派生种子),由 LegacyRandomSource mixin 读取。 */
    private static final ThreadLocal<RandomSource> THREAD_LOCAL_RANDOM = new ThreadLocal<>();

    private ChunkEnvParallelScheduler() {
    }

    public static ThreadLocal<RandomSource> threadLocalRandom() {
        return THREAD_LOCAL_RANDOM;
    }

    private static ThreadPoolExecutor pool() {
        ThreadPoolExecutor p = pool;
        if (p == null || p.isShutdown()) {
            synchronized (ChunkEnvParallelScheduler.class) {
                p = pool;
                if (p == null || p.isShutdown()) {
                    int threads = PRTSFeaturesConfig.chunkEnvThreads > 0
                            ? PRTSFeaturesConfig.chunkEnvThreads
                            : Math.max(2, Runtime.getRuntime().availableProcessors());
                    p = new ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS,
                            new ArrayBlockingQueue<>(4096), r -> {
                        // 8MB 栈与 JVM 主线程一致：流体/随机 tick 深递归在 1MB 默认栈上会 StackOverflowError
                        Thread t = new Thread(null, r, "PRTS-ChunkEnvTick", 8L * 1024 * 1024);
                        t.setDaemon(true);
                        return t;
                    }, new ThreadPoolExecutor.CallerRunsPolicy());
                    pool = p;
                }
            }
        }
        return p;
    }

    /** tickChunks HEAD 调用:开启本线程收集窗口(主线程或维度 worker)。 */
    public static void begin(boolean onTickThread) {
        if (!PRTSFeaturesConfig.chunkEnvParallel || !onTickThread) {
            return;
        }
        PendingWindow w = WINDOW.get();
        w.active = true;
        w.tasks.clear();
    }

    /** @Redirect level.tickChunk:并行时收集;返回 true=已收集。 */
    public static boolean collect(ServerLevel level, LevelChunk chunk, int randomTickSpeed) {
        PendingWindow w = WINDOW.get();
        if (!w.active) {
            return false;
        }
        w.tasks.add(new Object[]{level, chunk, randomTickSpeed});
        return true;
    }

    /** 广播前收口:提交本线程收集的 chunk 环境 tick,等待完成。 */
    public static void flush() {
        PendingWindow w = WINDOW.get();
        if (!w.active || w.tasks.isEmpty()) {
            w.active = false;
            return;
        }
        List<Object[]> tasks = new ArrayList<>(w.tasks);
        w.tasks.clear();
        w.active = false;
        CountDownLatch latch = new CountDownLatch(tasks.size());
        for (Object[] e : tasks) {
            ServerLevel level = (ServerLevel) e[0];
            LevelChunk chunk = (LevelChunk) e[1];
            int speed = (Integer) e[2];
            pool().execute(() -> runTask(level, chunk, speed, latch));
        }
        try {
            if (!latch.await(10L, TimeUnit.SECONDS)) {
                // 超时后任务仍在写区块：绝不带着运行中的 worker 进入后续阶段。
                // 与 barrier 硬超时同语义：dump 全线程后崩服，防止静默状态撕裂。
                LOGGER.fatal("[chunk-env] parallel tick barrier timeout ({} tasks); dumping threads", tasks.size());
                Thread.getAllStackTraces().forEach((thread, stack) -> {
                    LOGGER.fatal("  thread {} state={}", thread.getName(), thread.getState());
                    for (StackTraceElement el : stack) {
                        LOGGER.fatal("    at {}", el);
                    }
                });
                throw new IllegalStateException("[chunk-env] barrier timeout, refusing to continue with running workers");
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private static void runTask(ServerLevel level, LevelChunk chunk, int randomTickSpeed, CountDownLatch latch) {
        ChunkPos pos = chunk.getPos();
        try {
            // 3×3 区块锁:互斥相邻 chunk 的并发写(流体跨界/邻居更新反应)。
            int[] keys = lockKeys(pos.x, pos.z);
            for (int key : keys) {
                LOCKS[key].lock();
            }
            try {
                // per-chunk 派生种子:分布与原版一致,同 chunk 序列可复现。
                long seed = level.getSeed() ^ (pos.x * 341873128712L + pos.z * 132897987541L);
                THREAD_LOCAL_RANDOM.set(new LegacyRandomSource(seed));
                // 线程身份:区块所属 region,使 setBlock 跨区写/计划刻/实体新增走既有 worker 路径。
                RegionTickManager.enterChunkEnvContext(level, RegionLevel.regionId(pos));
                try {
                    level.tickChunk(chunk, randomTickSpeed);
                } finally {
                    RegionTickManager.exitRegionContext();
                    THREAD_LOCAL_RANDOM.remove();
                }
            } finally {
                for (int i = keys.length - 1; i >= 0; i--) {
                    LOCKS[keys[i]].unlock();
                }
            }
        } catch (Throwable t) {
            // 单 chunk 环境 tick 异常不影响其他 chunk 与服务器。
            LOGGER.error("[chunk-env] chunk {} tick failed", pos, t);
        } finally {
            latch.countDown();
        }
    }

    /** 3×3 区块锁 key 数组(排序,防死锁)。 */
    private static int[] lockKeys(int chunkX, int chunkZ) {
        int[] keys = new int[(1 + 2 * LOCK_RADIUS) * (1 + 2 * LOCK_RADIUS)];
        int idx = 0;
        for (int dx = -LOCK_RADIUS; dx <= LOCK_RADIUS; dx++) {
            for (int dz = -LOCK_RADIUS; dz <= LOCK_RADIUS; dz++) {
                long packed = ChunkPos.asLong(chunkX + dx, chunkZ + dz);
                keys[idx++] = (int) Math.floorMod(packed, (long) LOCK_STRIPES);
            }
        }
        Arrays.sort(keys);
        return keys;
    }

    /** 仅用于单元级调试查询(状态行可扩展)。 */
    static int pendingSize() {
        return WINDOW.get().tasks.size();
    }

    /** 单维度收集窗口（tickChunks 每维度单线程，无需额外同步）。 */
    private static final class PendingWindow {
        boolean active;
        final List<Object[]> tasks = new ArrayList<>();
    }
}
