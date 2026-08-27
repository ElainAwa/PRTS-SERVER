/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.optimization.general.chunksystem;

import io.izzel.arclight.common.compat.prts.PRTSFeaturesConfig;
import io.izzel.arclight.common.optimization.general.chunksystem.scheduler.ExecutorManager;
import io.izzel.arclight.common.optimization.general.chunksystem.scheduler.LockToken;
import io.izzel.arclight.common.optimization.general.chunksystem.scheduler.Task;
import io.izzel.arclight.common.optimization.general.servercore.DimensionTickManager;
import net.minecraft.server.level.ChunkGenerationTask;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 区块系统调度器门面（M1：FlowSched 精简移植驱动原版生成 future 链）。
 *
 * <p>接入点：{@code ChunkMap.runGenerationTask} 原版把生成任务消息丢进
 * worldgen 邮箱（FIFO）；本调度器改经 {@link ExecutorManager} 的 64 级优先级队列，
 * 状态推进仍由原版 {@code ChunkGenerationTask.runUntilWait()} 的 future 链完成，
 * 只替换「执行顺序与并发」这一层。
 *
 * <p>优先级：主线程提交时按到最近玩家的切比雪夫距离（0..63）定档
 * （借鉴 C2ME SchedulingManager syncLoad 距离模型）；续段重入队沿用提交时
 * 优先级，避免跨线程读玩家列表。
 *
 * <p>锁：锁域半径可配（默认 2 = 5×5）。测服三轮实测定性：仅锁中心块时 feature 阶段
 * 跨区块写（OreFeature 经 BulkSectionAccess 写邻居 section）触发 PalettedContainer
 * 并发写，轻则 ThreadingDetector 报错误致虚空，重则 Semaphore(1) 循环等待全员死锁；
 * 半径 1（3×3）仍复现（dump 实锤两个 PRTS-ChunkSystem worker 冲突），写半径达 2，
 * 即 ChunkStatus.FULL 累计生成半径（半径随 M2 状态机重写再精确化）。
 * 任务同步段结束即释放锁，等待依赖时不持锁（与原版邮箱「不阻塞泵」语义一致）。
 *
 * <p>依赖门控（阶段四，{@code dep-gating} 配置灰度）：原版 {@code waitForScheduledLayer}
 * 只等尾部第一个未完成 future，尾完成即唤醒续段，层内其余 future 未就绪时立即再挂起，
 * 每次空转往返都付出排队+取锁代价。门控改为挂起时收集当前层全部未完成 future，
 * 全完成后才经单一入队点（{@code inQueued} CAS 幂等）重新入队，邻块未达所需状态不排队。
 * 续段复用同一任务实例，跨唤醒源天然去重；换锁暂停段不入门控（暂停即换锁重排队）。
 *
 * <p>两阶段锁域拆分（阶段二，{@code split-stages} 配置灰度）：5×5 锁域把 features 段
 * 并行度压到 ~4/8（飞行实测：busy=5%、每任务 8.4 次锁失败）。拆分后任务先以中心块锁跑
 * features 前步骤（原版写半径声明均为 0，邻块读由 future 依赖链保序——读依赖审计通过），
 * 进入 FEATURES 层前经 {@code ChunkGenerationTaskMixin_LockSplit} 注入一次性暂停（哨兵
 * future），本调度器识别后释放窄锁、以宽锁（{@code lock-radius}）重新入队续跑。
 */
public final class ChunkSystemScheduler {

    private static final Logger LOGGER = LogManager.getLogger("PRTS-ChunkSystem");
    private static final int MAX_PRIORITY = 63;
    private static final AtomicInteger THREAD_COUNTER = new AtomicInteger(0);

    /** 换锁暂停哨兵：由换锁 mixin 返回，调度器按身份识别（永不 complete）。 */
    private static final CompletableFuture<?> LOCK_UPGRADE_PAUSE = new CompletableFuture<>();
    /** 当前线程的同步段是否持窄锁（中心块）：换锁 mixin 的暂停判定依据。 */
    private static final ThreadLocal<Boolean> NARROW_LOCK_ACTIVE = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private static volatile ExecutorManager executor;

    private ChunkSystemScheduler() {
    }

    /** M1/M2.1 共用的执行器（配置互斥，同一时刻只有一条路径驱动）。 */
    public static ExecutorManager executor() {
        ExecutorManager instance = executor;
        if (instance == null) {
            synchronized (ChunkSystemScheduler.class) {
                instance = executor;
                if (instance == null) {
                    int workers = Math.max(1, PRTSFeaturesConfig.chunkSystemSchedulerWorkers);
                    instance = new ExecutorManager(workers, thread -> {
                        thread.setDaemon(true);
                        thread.setPriority(Thread.NORM_PRIORITY - 1);
                        thread.setName("PRTS-ChunkSystem-" + THREAD_COUNTER.getAndIncrement());
                    });
                    executor = instance;
                    ChunkSystemStats.initScheduler(workers);
                    LOGGER.info("[chunk-system] scheduler started: workers={} priorities=64 (shared by M1 flowsched / M2 statemachine)", workers);
                }
            }
        }
        return instance;
    }

    /**
     * 提交原版生成任务。必须在该维度的主事件循环线程调用（读玩家列表算优先级）；
     * 维度并行下即维度 tick 线程，而非服务端主线程。
     */
    public static void submit(ServerLevel level, ChunkGenerationTask task) {
        // 维度并行下 runGenerationTask 跑在维度 tick 线程（该维度的主事件循环），
        // 否则在服务端主线程；两者之外一律拒绝（fail-fast 守卫）。
        if (!level.getServer().isSameThread() && !DimensionTickManager.isDimensionTickThread()) {
            throw new IllegalStateException("[chunk-system] submit must be called on the level's main thread");
        }
        ChunkPos pos = task.getCenter().getPos();
        int priority = priorityFor(level, pos.x, pos.z);
        // 两阶段拆分开启时先以中心块锁提交（窄锁段）；进入 FEATURES 层前由
        // 换锁 mixin 暂停并换宽锁续跑。关闭时直接全宽锁域（现行行为）。
        boolean narrow = PRTSFeaturesConfig.chunkSystemSchedulerSplitStages;
        ChunkLockToken[] tokens = narrow
                ? new ChunkLockToken[]{new ChunkLockToken(level.dimension(), pos.toLong())}
                : wideTokens(level.dimension(), pos);
        executor().schedule(new ChunkGenTask(level.dimension(), task, pos, priority, tokens,
                System.nanoTime(), narrow, 0, null, 0));
        ChunkSystemStats.submitted(priority);
    }

    /**
     * IO 反序列化执行入口（M2.2）：{@code ChunkSerializer.read} 移出主线程后的落点。
     *
     * <p>专用单线程（非调度器优先级池）：反序列化内 {@code PoiManager
     * .checkConsistencyWithBlocks} 会经 {@code SectionStorage.getOrLoad} 并发写共享的
     * 非并发 {@code Long2ObjectOpenHashMap}（1.21.1 实测复现：多线程并发反序列化时
     * {@code rehash} 抛 {@code ArrayIndexOutOfBoundsException}），故反序列化段串行；
     * 磁盘读/NBT 解压仍在原版 IOWorker 线程（每存储一个），本线程只做对象图构建。
     * 调度器未启用时返回 null（调用方回退原版主线程路径）。
     */
    public static java.util.concurrent.Executor ioDeserializeExecutor() {
        if (!PRTSFeaturesConfig.chunkSystemSchedulerEnabled) {
            return null;
        }
        return IoDeserializeHolder.EXECUTOR;
    }

    /** 反序列化专用单线程（懒加载；守护线程，随 JVM 退出）。 */
    private static final class IoDeserializeHolder {
        private static final java.util.concurrent.ExecutorService EXECUTOR =
                java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "PRTS-ChunkSystem-IoDeserialize");
                    t.setDaemon(true);
                    return t;
                });
    }

    /** 宽锁域 = 中心 ± lockRadius（features 跨块写覆盖，实测半径 2）。 */
    private static ChunkLockToken[] wideTokens(ResourceKey<Level> dimension, ChunkPos pos) {
        int radius = PRTSFeaturesConfig.chunkSystemSchedulerLockRadius;
        ChunkLockToken[] tokens = new ChunkLockToken[(2 * radius + 1) * (2 * radius + 1)];
        int i = 0;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                tokens[i++] = new ChunkLockToken(dimension,
                        new ChunkPos(pos.x + dx, pos.z + dz).toLong());
            }
        }
        return tokens;
    }

    /** 换锁 mixin 查询：当前线程同步段是否持窄锁。 */
    public static boolean isNarrowLockActive() {
        return NARROW_LOCK_ACTIVE.get();
    }

    /** 换锁暂停哨兵（身份比较）。 */
    public static CompletableFuture<?> lockUpgradePause() {
        return LOCK_UPGRADE_PAUSE;
    }

    /** 切比雪夫距离定档：0=最近（≤1 块），63=最远/无玩家。 */
    static int priorityFor(ServerLevel level, int x, int z) {
        int min = MAX_PRIORITY;
        for (ServerPlayer player : level.players()) {
            int dx = Math.abs(Math.floorDiv(player.getBlockX(), 16) - x);
            int dz = Math.abs(Math.floorDiv(player.getBlockZ(), 16) - z);
            int dist = Math.max(dx, dz);
            if (dist < min) {
                min = dist;
            }
            if (min == 0) {
                break;
            }
        }
        return Math.min(min, MAX_PRIORITY);
    }

    public static String statusText() {
        String state = PRTSFeaturesConfig.chunkSystemEnabled ? "statemachine-m2"
                : PRTSFeaturesConfig.chunkSystemSchedulerEnabled ? "flowsched" : "vanilla";
        return "state=" + state + " " + ChunkSystemStats.statusText();
    }

    /** 区块写锁令牌：维度 + packed pos 判重（按生成圆域展开成方块）。 */
    public record ChunkLockToken(ResourceKey<Level> dimension, long pos) implements LockToken {
    }

    /**
     * 生成任务包装：同步跑 {@code runUntilWait()} 一段，遇等待则挂 future 续段；
     * 锁只覆盖同步段。续段复用本实例（阶段四）：所有唤醒源经 {@link #enqueue()}
     * 的 {@code inQueued} CAS 单一入队，重复唤醒无害；出队执行时复位。
     *
     * <p>{@code firstSubmitNanos} 贯穿续段，用于端到端延迟遥测；
     * {@code lockWaitStartNanos} 记录首次出队尝试时刻，跨 listener 重试保留，
     * 每次入队时复位。{@code narrowLock} 标记本段持窄锁（中心块）：供换锁 mixin 判定暂停。
     * {@code suspendNanos/waitingStatus} 记录挂起时刻与当时状态层（依赖等待遥测，
     * 首段为 0/null）；{@code resubCount} 累计续段次数（完成时记分布）。
     */
    private static final class ChunkGenTask implements Task {

        private final ResourceKey<Level> dimension;
        private final ChunkGenerationTask gen;
        private final ChunkLockToken[] lockTokens;
        private final ChunkPos center;
        private final int priority;
        private final long firstSubmitNanos;
        private final boolean narrowLock;
        /** 单一入队点幂等闸：在队/在 listener 等待期间为 true，出队执行时复位。 */
        private final AtomicBoolean inQueued = new AtomicBoolean(false);
        private long enqueuedAtNanos;
        private long suspendNanos;
        private ChunkStatus waitingStatus;
        private int resubCount;
        private volatile long lockWaitStartNanos;

        ChunkGenTask(ResourceKey<Level> dimension, ChunkGenerationTask gen, ChunkPos center,
                     int priority, ChunkLockToken[] lockTokens, long firstSubmitNanos, boolean narrowLock,
                     long suspendNanos, ChunkStatus waitingStatus, int resubCount) {
            this.dimension = dimension;
            this.gen = gen;
            this.center = center;
            this.priority = priority;
            this.lockTokens = lockTokens;
            this.firstSubmitNanos = firstSubmitNanos;
            this.narrowLock = narrowLock;
            this.suspendNanos = suspendNanos;
            this.waitingStatus = waitingStatus;
            this.resubCount = resubCount;
            this.enqueuedAtNanos = System.nanoTime();
        }

        /** 单一入队点：任何唤醒源（依赖完成/换锁/锁释放）都经此入队，CAS 防重复。 */
        private void enqueue() {
            if (inQueued.compareAndSet(false, true)) {
                enqueuedAtNanos = System.nanoTime();
                lockWaitStartNanos = 0;
                executor().schedule(this);
            }
        }

        @Override
        public void lockWaitMark(long nanos) {
            if (lockWaitStartNanos == 0) {
                lockWaitStartNanos = nanos;
            }
        }

        @Override
        public void lockWaitAcquired(long nowNanos) {
            long start = lockWaitStartNanos;
            if (start != 0) {
                ChunkSystemStats.lockWait(nowNanos - start);
            }
        }

        @Override
        public void run(Runnable releaseLocks) {
            inQueued.set(false); // 出队执行：复位入队闸，后续唤醒可再次入队
            long start = System.nanoTime();
            if (suspendNanos != 0) {
                ChunkSystemStats.depWait(waitingStatus, start - suspendNanos);
            }
            try {
                // 原版 future 链推进：同步执行至等待点；等待的依赖由其它任务的
                // completeFuture 完成，与本调度器无环依赖（与原版邮箱语义一致）。
                CompletableFuture<?> wait;
                NARROW_LOCK_ACTIVE.set(narrowLock);
                try {
                    wait = gen.runUntilWait();
                } finally {
                    NARROW_LOCK_ACTIVE.remove();
                }
                long queueWait = start - enqueuedAtNanos;
                ChunkSystemStats.executed(System.nanoTime() - start, queueWait);
                if (wait == LOCK_UPGRADE_PAUSE) {
                    // 换锁暂停：释放窄锁（先于重入队，避免新段无谓的锁失败），
                    // 标记任务已升级，以宽锁重新入队续跑。
                    ((PRTSLockSplitAware) gen).prts$setUpgraded(true);
                    releaseLocks.run();
                    ChunkSystemStats.resubmitted();
                    ChunkSystemStats.lockUpgrade();
                    // 换锁暂停非依赖等待：不携带挂起时刻（续段不计 depWait）。
                    executor().schedule(new ChunkGenTask(dimension, gen, center, priority,
                            wideTokens(dimension, center), firstSubmitNanos, false,
                            0, null, resubCount + 1));
                } else if (wait != null) {
                    ChunkSystemStats.resubmitted();
                    // 与原版一致：无条件续段，取消标记由 runUntilWait 内部检查。
                    // 挂起时刻与当时状态层记录在本实例，供依赖等待遥测归因。
                    resubCount++;
                    suspendNanos = System.nanoTime();
                    waitingStatus = gen instanceof PRTSLockSplitAware aware
                            ? aware.prts$getScheduledStatus() : null;
                    parkUntilDependenciesReady(wait);
                } else {
                    ChunkSystemStats.taskCompleted(System.nanoTime() - firstSubmitNanos);
                    ChunkSystemStats.resubDist(resubCount);
                }
            } finally {
                releaseLocks.run();
            }
        }

        /**
         * 依赖门控挂起：邻块未达所需状态不排队。
         * 开启时收集当前层全部未完成 future，全完成后才经单一入队点入队（消除层内空转）；
         * 异常完成（取消/卸载）同样触发唤醒，{@code runUntilWait} 内部处置。
         * 关闭或收集失败（mixin 未生效）时退回原版尾 future 唤醒，语义不变。
         */
        private void parkUntilDependenciesReady(CompletableFuture<?> wait) {
            List<CompletableFuture<?>> pending = gen instanceof PRTSLockSplitAware aware
                    ? aware.prts$collectPendingLayerFutures() : List.of();
            if (PRTSFeaturesConfig.chunkSystemSchedulerDepGating && !pending.isEmpty()) {
                ChunkSystemStats.gatedSuspend(pending.size());
                CompletableFuture<?> gate = pending.size() == 1 ? pending.get(0)
                        : CompletableFuture.allOf(pending.toArray(new CompletableFuture<?>[0]));
                gate.whenComplete((result, throwable) -> enqueue());
            } else {
                wait.whenComplete((result, throwable) -> enqueue());
            }
        }

        @Override
        public void propagateException(Throwable t) {
            ChunkSystemStats.exception();
            LOGGER.error("[chunk-system] generation task failed at {} (dim={})",
                    center, dimension.location(), t);
        }

        @Override
        public LockToken[] lockTokens() {
            return lockTokens;
        }

        @Override
        public int priority() {
            return priority;
        }

        @Override
        public String workLabel() {
            return "gen-task @ " + center + " dim=" + dimension.location();
        }
    }
}
