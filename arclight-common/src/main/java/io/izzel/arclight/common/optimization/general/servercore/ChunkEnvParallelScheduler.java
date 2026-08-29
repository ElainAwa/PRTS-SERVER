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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 区块环境 tick(随机 tick/流体)并行:主线程 tickChunks 内收集各 chunk,
 * 在广播前提交到独立子任务池并行执行真实 {@code ServerLevel.tickChunk}。
 *
 * <p>安全设计:<ul>
 *   <li>阶段隔离:vanilla 顺序为方块刻→ServerChunkCache.tick→runBlockEvents→实体循环,
 *       本阶段与 region worker 各阶段互不重叠,同 level 无其他写者;</li>
 *   <li>跨 chunk 写(流体跨界、邻居更新反应)用 3×3 区块锁(排序加锁)互斥;</li>
 *   <li>随机源:每任务 per-chunk 派生种子,经 LegacyRandomSource 线程本地分支生效,
 *       分布与原版一致且同 chunk 序列可复现;</li>
 *   <li>子任务设置 REGION_CONTEXT(区块所属 region),setBlock 跨区写走 journal、
 *       计划刻走主线程 deferral、实体新增走 EntityAddDefer——全部复用既有 worker 路径;</li>
 *   <li>雷击/冰雪等 addFreshEntity 走既有 defer;tickChunk 装饰器(thunderChance/闪电事件
 *       /事件归因)全部保留(调用的是真实 tickChunk)。</li>
 * </ul>
 */
public final class ChunkEnvParallelScheduler {

    private static final Logger LOGGER = LogManager.getLogger("Arclight");

    /** 子任务池(独立于 region/dimension 池,避免 await 时池线程被占导致的饿死)。 */
    private static volatile ThreadPoolExecutor pool;
    /** 收集窗口：tickChunks 只在维度 tick 线程跑（每维度一线程），ThreadLocal 天然隔离多维度。 */
    private static final ThreadLocal<PendingWindow> WINDOW = ThreadLocal.withInitial(PendingWindow::new);
    /** 每 chunk 锁表(radius=1 时锁定 3×3)。 */
    private static final ConcurrentHashMap<Long, ReentrantLock> LOCKS = new ConcurrentHashMap<>();
    /** 3×3 锁半径常量:覆盖流体跨界写与邻居更新反应写。 */
    private static final int LOCK_RADIUS = 1;

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
                            new LinkedBlockingQueue<>(), r -> {
                        // 8MB 栈与 JVM 主线程一致：流体/随机 tick 深递归在 1MB 默认栈上会 StackOverflowError
                        Thread t = new Thread(null, r, "PRTS-ChunkEnvTick", 8L * 1024 * 1024);
                        t.setDaemon(true);
                        return t;
                    });
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
                LOGGER.warn("[chunk-env] parallel tick barrier timeout ({} tasks), continuing", tasks.size());
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private static void runTask(ServerLevel level, LevelChunk chunk, int randomTickSpeed, CountDownLatch latch) {
        ChunkPos pos = chunk.getPos();
        try {
            // 3×3 区块锁:互斥相邻 chunk 的并发写(流体跨界/邻居更新反应)。
            long[] keys = lockKeys(pos.x, pos.z);
            for (long key : keys) {
                LOCKS.computeIfAbsent(key, k -> new ReentrantLock()).lock();
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
                    ReentrantLock lock = LOCKS.get(keys[i]);
                    if (lock != null) {
                        lock.unlock();
                    }
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
    private static long[] lockKeys(int chunkX, int chunkZ) {
        long[] keys = new long[(1 + 2 * LOCK_RADIUS) * (1 + 2 * LOCK_RADIUS)];
        int idx = 0;
        for (int dx = -LOCK_RADIUS; dx <= LOCK_RADIUS; dx++) {
            for (int dz = -LOCK_RADIUS; dz <= LOCK_RADIUS; dz++) {
                long k = ((long) (chunkX + dx) << 32) ^ (chunkZ + dz & 0xFFFFFFFFL);
                keys[idx++] = k;
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
