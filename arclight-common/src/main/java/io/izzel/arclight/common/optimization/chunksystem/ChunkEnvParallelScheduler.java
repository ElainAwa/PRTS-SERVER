/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.optimization.chunksystem;

import io.izzel.arclight.common.compat.prts.PRTSFeaturesConfig;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.util.RandomSource;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import io.izzel.arclight.common.optimization.parallel.RegionLevel;
import io.izzel.arclight.common.optimization.parallel.RegionTickManager;

/** 区块环境 tick 并行：收集 chunk 后在独立池执行，条带锁隔离相邻区块写。 */
public final class ChunkEnvParallelScheduler {

    private static final Logger LOGGER = LogManager.getLogger("Arclight");

    /** 子任务池(独立于 region/dimension 池,避免 await 时池线程被占导致的饿死)。 */
    private static volatile ThreadPoolExecutor pool;
    /** 收集窗口：tickChunks 只在维度 tick 线程跑（每维度一线程），ThreadLocal 天然隔离多维度。 */
    private static final ThreadLocal<PendingWindow> WINDOW = ThreadLocal.withInitial(PendingWindow::new);
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
        runPhased(tasks);
    }

    /**
     * 相位批处理执行（无锁）：冲突模型是"两个 chunk 的 3×3 写区相交"（切比雪夫距离 ≤2）。
     * 按行（chunkZ）分组后分 3 个相位（z mod 3）：同相位内各行 z 距离 ≥3，写区必不相交；
     * 行内按 x 顺序串行执行。互斥性与原"每块抢 9 把 3×3 条带锁"等价（且更严格：
     * 同行内不再并行），但每 tick 的锁开销从 18×N 次 lock/unlock 降为 0
     * ——JFR 实测旧实现的 ReentrantLock CAS/获取释放占 ~32% 采样。
     */
    private static void runPhased(List<Object[]> tasks) {
        Map<Integer, List<Object[]>> rows = new TreeMap<>();
        for (Object[] e : tasks) {
            rows.computeIfAbsent(((LevelChunk) e[1]).getPos().z, k -> new ArrayList<>()).add(e);
        }
        for (List<Object[]> row : rows.values()) {
            row.sort(Comparator.comparingInt(e -> ((LevelChunk) e[1]).getPos().x));
        }
        for (int phase = 0; phase < 3; phase++) {
            List<List<Object[]>> phaseRows = new ArrayList<>();
            for (Map.Entry<Integer, List<Object[]>> entry : rows.entrySet()) {
                if (Math.floorMod(entry.getKey(), 3) == phase) {
                    phaseRows.add(entry.getValue());
                }
            }
            if (phaseRows.isEmpty()) {
                continue;
            }
            CountDownLatch latch = new CountDownLatch(phaseRows.size());
            for (List<Object[]> row : phaseRows) {
                pool().execute(() -> runRow(row, latch));
            }
            awaitBarrier(latch, tasks.size(), phase);
        }
    }

    private static void awaitBarrier(CountDownLatch latch, int taskCount, int phase) {
        try {
            if (!latch.await(10L, TimeUnit.SECONDS)) {
                // 超时后任务仍在写区块：绝不带着运行中的 worker 进入后续阶段。
                // 与 barrier 硬超时同语义：dump 全线程后崩服，防止静默状态撕裂。
                LOGGER.fatal("[chunk-env] parallel tick barrier timeout ({} tasks, phase {}); dumping threads",
                        taskCount, phase);
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

    /** 执行同一行（同 chunkZ）的一批 chunk 环境 tick：行内串行，避免相邻写区并发。 */
    private static void runRow(List<Object[]> row, CountDownLatch latch) {
        try {
            for (Object[] e : row) {
                ServerLevel level = (ServerLevel) e[0];
                LevelChunk chunk = (LevelChunk) e[1];
                int randomTickSpeed = (Integer) e[2];
                ChunkPos pos = chunk.getPos();
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
                } catch (Throwable t) {
                    // 单 chunk 环境 tick 异常不影响其他 chunk 与服务器。
                    LOGGER.error("[chunk-env] chunk {} tick failed", pos, t);
                }
            }
        } finally {
            latch.countDown();
        }
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
