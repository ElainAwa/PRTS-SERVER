/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.optimization.chunksystem;

import io.izzel.arclight.common.compat.prts.PRTSFeaturesConfig;
import io.izzel.arclight.common.optimization.chunksystem.scheduler.ExecutorManager;
import io.izzel.arclight.common.optimization.chunksystem.scheduler.LockToken;
import io.izzel.arclight.common.optimization.chunksystem.scheduler.Task;
import io.izzel.arclight.common.optimization.parallel.DimensionTickManager;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ChunkGenerationTask;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.server.level.GeneratingChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.storage.ChunkStorage;
import net.minecraft.world.level.chunk.status.ChunkDependencies;
import net.minecraft.world.level.chunk.status.ChunkPyramid;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.status.ChunkStep;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** 单区块×单状态任务图驱动器：细粒度调度生成步骤，依赖与锁按写半径展开。 */
public final class ChunkSystemDriver {

    private static final Logger LOGGER = LogManager.getLogger("PRTS-ChunkSystem");

    /** 全局共享驱动任务表：跨驱动器去重，任务对象唯一。 */
    private static final ConcurrentHashMap<TaskKey, StatusStepTask> SHARED_TASKS = new ConcurrentHashMap<>();

    /** 全局"状态 future 槽位被清空"纪元：任何 holder 的 failAndClear 都会 +1。 */
    private static final AtomicLong FUTURE_CLEAR_EPOCH = new AtomicLong();

    /** 槽位被清空（换票/卸载）时由 holder mixin 调用。 */
    public static void futureCleared() {
        FUTURE_CLEAR_EPOCH.incrementAndGet();
    }

    /** 无覆盖缓存时的占位门：只有 park 看门狗的重评估能唤醒它（绝不用不覆盖的缓存硬跑）。 */
    private static final CompletableFuture<ChunkResult<ChunkAccess>> STEP_CACHE_MISS_GATE =
            new CompletableFuture<>();

    /** 全局重调度请求去重：防多个驱动器对未来重复投递 reschedule（跨驱动器去重）。 */
    private static final ConcurrentHashMap.KeySetView<TaskKey, Boolean> SHARED_DEFERRED =
            ConcurrentHashMap.newKeySet();

    /** 诊断日志限流窗口起点 / 窗口内已发条数：风暴期数千任务同时挂起时防刷屏淹没有效证据。 */
    private static final AtomicLong DIAG_WINDOW_START_NANOS = new AtomicLong(System.nanoTime());
    private static final AtomicInteger DIAG_WINDOW_COUNT = new AtomicInteger();
    private static final int DIAG_WINDOW_LIMIT = 5;
    private static final long DIAG_WINDOW_NANOS = 10_000_000_000L;

    /** 诊断日志配额：每 10s 最多 {@value #DIAG_WINDOW_LIMIT} 条，超出的抑制并汇总。 */
    private static boolean diagLogAllowed() {
        long now = System.nanoTime();
        long start = DIAG_WINDOW_START_NANOS.get();
        if (now - start > DIAG_WINDOW_NANOS && DIAG_WINDOW_START_NANOS.compareAndSet(start, now)) {
            int suppressed = DIAG_WINDOW_COUNT.getAndSet(0);
            if (suppressed > 0) {
                LOGGER.warn("[chunk-system] {} park diagnostics suppressed in the last {}s (storm throttling)",
                        suppressed, DIAG_WINDOW_NANOS / 1_000_000_000L);
            }
        }
        return DIAG_WINDOW_COUNT.incrementAndGet() <= DIAG_WINDOW_LIMIT;
    }

    /** Blender 旧区块探测预热单线程。 */
    private static final ExecutorService BLENDER_PREWARM = new ThreadPoolExecutor(1, 1,
            0L, TimeUnit.MILLISECONDS,
            new java.util.concurrent.ArrayBlockingQueue<>(64),
            r -> {
                Thread thread = new Thread(r, "PRTS-BlenderPrewarm");
                thread.setDaemon(true);
                return thread;
            },
            new ThreadPoolExecutor.DiscardOldestPolicy());

    private final ServerLevel level;
    private final ResourceKey<Level> dimension;
    private final GeneratingChunkMap chunkMap;
    private final ChunkGenerationTask genTask;
    private final PRTSChunkSystemTaskAware taskAware;
    private final StaticCache2D<GenerationChunkHolder> cache;
    private final GenerationChunkHolder centerHolder;
    private final ChunkPos center;
    private final ChunkStatus target;
    private final int priority;
    private final long submitNanos;

    /** 本驱动器已采纳的共享任务表，只做结算去重。 */
    private final ConcurrentHashMap<TaskKey, StatusStepTask> tasks = new ConcurrentHashMap<>();
    /** 待结算单元计数：决策占 1 位 + 每个已采纳任务 1 位；归零释放锥域持有。 */
    private final AtomicInteger pending = new AtomicInteger(1);
    private final AtomicBoolean claimReleased = new AtomicBoolean(false);
    /** needsGeneration 决策结果；generate 分支任务工作前必须等它完成。 */
    private final CompletableFuture<Boolean> decisionFuture = new CompletableFuture<>();
    private volatile boolean needsGeneration;

    private ChunkSystemDriver(ServerLevel level, ChunkMap chunkMap, ChunkGenerationTask genTask) {
        this.level = level;
        this.dimension = level.dimension();
        this.chunkMap = chunkMap;
        this.genTask = genTask;
        this.taskAware = (PRTSChunkSystemTaskAware) genTask;
        this.cache = this.taskAware.prts$cache();
        this.centerHolder = genTask.getCenter();
        this.center = this.centerHolder.getPos();
        this.target = genTask.targetStatus;
        // 主线程正阻塞等待本块（forceload / 进服强制加载 / 模组同步读）：整个锥域走紧急通道
        // （最高档 = 队列里最先出队）。远处坐标否则会被 clamp 到需求档上限，与上千个预铺任务
        // 同档排队（实测单块等待 231s）。依赖序不受影响：门 + 执行前物化校验决定"能不能跑"，
        // 优先级只决定"谁先拿到 CPU"，抢跑只会多一次 park 重排，不会踩未物化依赖。
        if (MainThreadChunkWaits.isAwaited(level, this.center)) {
            this.priority = 0;
            this.submitNanos = System.nanoTime();
            return;
        }
        // 视距外预铺区按距离倒序定档，保证走廊深处先跑
        int dist = ChunkSystemScheduler.priorityFor(level, this.center.x, this.center.z);
        if (this.target == ChunkStatus.FULL) {
            int viewDist = level.getServer().getPlayerList().getViewDistance();
            int zoneEnd = viewDist + PRTSFeaturesConfig.chunkPrefetchWindow;
            if (dist > viewDist && dist <= zoneEnd) {
                this.priority = zoneEnd - dist + 1;
            } else {
                this.priority = dist;
            }
        } else {
            this.priority = this.target == ChunkStatus.STRUCTURE_STARTS
                    ? PRTSFeaturesConfig.chunkPrefetchPriority
                    : dist;
        }
        this.submitNanos = System.nanoTime();
    }

    /** 提交原版生成任务并展开任务图，必须在维度事件循环线程调用。 */
    public static void submit(ServerLevel level, ChunkMap chunkMap, ChunkGenerationTask task) {
        if (!level.getServer().isSameThread() && !DimensionTickManager.isDimensionTickThread()) {
            throw new IllegalStateException("[chunk-system] submit must be called on the level's main thread");
        }
        new ChunkSystemDriver(level, chunkMap, task).start();
    }

    private void start() {
        // 预热旧区块探测，把首次 I/O 移出生成关键路径
        // 只捕获 chunkMap/center，避免 pending lambda 强引用 driver
        GeneratingChunkMap prewarmMap = this.chunkMap;
        ChunkPos prewarmCenter = this.center;
        BLENDER_PREWARM.execute(() -> {
            try {
                ((ChunkStorage) prewarmMap).isOldChunkAround(prewarmCenter, 8);
            } catch (Throwable ignored) {
                // 预热只是优化；失败时后续原版路径仍会按需探测。
            }
        });
        // 内圈半径 = LOADING 锥域对 EMPTY 的累计半径（原版首层调度半径，
        // 实测 1.21.1 各目标均为 1；target=EMPTY 时 getStepTo 对自身返回 0）。
        int innerRadius = this.target == ChunkStatus.EMPTY ? 0
                : ChunkPyramid.LOADING_PYRAMID.getStepTo(this.target).getAccumulatedRadiusOf(ChunkStatus.EMPTY);
        List<CompletableFuture<ChunkResult<ChunkAccess>>> innerEmpty = new ArrayList<>();
        for (int dx = -innerRadius; dx <= innerRadius; dx++) {
            for (int dz = -innerRadius; dz <= innerRadius; dz++) {
                innerEmpty.add(this.ensureTask(this.cache.get(this.center.x + dx, this.center.z + dz), ChunkStatus.EMPTY));
            }
        }
        CompletableFuture.allOf(innerEmpty.toArray(new CompletableFuture<?>[0]))
                .whenComplete((result, throwable) -> this.decide());
        ChunkSystemStats.submitted(this.priority);
    }

    /**
     * needsGeneration 决策 + 全图展开（内圈 EMPTY 全结算后的唯一入口）。
     * 可能跑在 worker 线程：{@link #ensureTask} 全程并发安全。
     */
    private void decide() {
        try {
            boolean loadable = this.canLoadWithoutGeneration();
            this.needsGeneration = !loadable;
            ChunkPyramid pyramid = this.needsGeneration ? ChunkPyramid.GENERATION_PYRAMID : ChunkPyramid.LOADING_PYRAMID;
            if (this.needsGeneration) {
                int coneRadius = ChunkPyramid.GENERATION_PYRAMID.getStepTo(this.target).getAccumulatedRadiusOf(ChunkStatus.EMPTY);
                for (int dx = -coneRadius; dx <= coneRadius; dx++) {
                    for (int dz = -coneRadius; dz <= coneRadius; dz++) {
                        this.ensureTask(this.cache.get(this.center.x + dx, this.center.z + dz), ChunkStatus.EMPTY);
                    }
                }
            }
            for (ChunkStatus status : ChunkStatus.getStatusList()) {
                if (status == ChunkStatus.EMPTY || status.isAfter(this.target)) {
                    continue;
                }
                int layerRadius = pyramid.getStepTo(this.target).getAccumulatedRadiusOf(status);
                for (int dx = -layerRadius; dx <= layerRadius; dx++) {
                    for (int dz = -layerRadius; dz <= layerRadius; dz++) {
                        this.ensureTask(this.cache.get(this.center.x + dx, this.center.z + dz), status);
                    }
                }
            }
            this.decisionFuture.complete(this.needsGeneration);
        } catch (Throwable t) {
            LOGGER.error("[chunk-system] decide failed for {} target={}", this.center, this.target, t);
            this.decisionFuture.complete(false);
        } finally {
            this.onUnitSettled(); // 决策占位计数归还
        }
    }

    /** 逐字移植原版 {@code ChunkGenerationTask.canLoadWithoutGeneration}。 */
    private boolean canLoadWithoutGeneration() {
        if (this.target == ChunkStatus.EMPTY) {
            return true;
        }
        ChunkStatus persisted = this.centerHolder.getPersistedStatus();
        if (persisted == null || persisted.isBefore(this.target)) {
            return false;
        }
        ChunkDependencies dependencies = ChunkPyramid.LOADING_PYRAMID.getStepTo(this.target).accumulatedDependencies();
        int radius = dependencies.getRadius();
        for (int x = this.center.x - radius; x <= this.center.x + radius; x++) {
            for (int z = this.center.z - radius; z <= this.center.z + radius; z++) {
                ChunkStatus required = dependencies.get(this.center.getChessboardDistance(x, z));
                ChunkStatus holderPersisted = this.cache.get(x, z).getPersistedStatus();
                if (holderPersisted == null || holderPersisted.isBefore(required)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * 确保 (holder, status) 有驱动任务。future 已结算则不再建任务
     * （外部路径已完成/失败该步）。返回该状态的 future（供门控收集）。
     */
    private CompletableFuture<ChunkResult<ChunkAccess>> ensureTask(GenerationChunkHolder holder, ChunkStatus status) {
        TaskKey key = new TaskKey(this.dimension, holder.getPos().x, holder.getPos().z, status.getIndex());
        this.tasks.computeIfAbsent(key, k -> {
            // 先物化 future（CAS 线程安全），再建共享任务；futureFor 的重调度判断
            // 放到共享任务创建之后（本调用已创建/采纳 → 不投递多余重调度）。
            final CompletableFuture<ChunkResult<ChunkAccess>> future =
                    ((PRTSChunkSystemHolderAware) holder).prts$getOrCreateFuture(status);
            if (future.isDone()) {
                return null;
            }
            // 只有"本驱动器锥域覆盖该步"或"本块自带覆盖的任务缓存"时才建任务：
            // 任务对象一旦建出就绑定创建者的锥域缓存（共享表按 (维度,坐标,状态) 去重，
            // 后续驱动器只是采纳、不会换缓存），用不覆盖的缓存跑大读半径的步会在
            // StaticCache2D 越界（生产 2026-09-17 实测 structure_references 越界 520+ 次）。
            // 不覆盖时交给"以本块为中心"的任务（尾部 futureFor 的延迟重调度）驱动。
            if (!ChunkSystemDriver.this.prts$canDrive(holder, status)) {
                return null;
            }
            // 任务对象全局共享，本驱动器只注册采纳与结算回调
            // 共享任务由首个创建者 enqueue，后续驱动器复用
            StatusStepTask task = SHARED_TASKS.computeIfAbsent(key, kk -> {
                StatusStepTask created = new StatusStepTask(holder, status, future);
                ChunkSystemStats.taskCreated();
                // 全局实活计数只随共享任务结算一次（多个采纳驱动器不得重复扣减）；
                // 结算后从共享表移除，防长跑内存泄漏（future 已 done，重建只会拿到 done）。
                future.whenComplete((result, throwable) -> {
                    ChunkSystemStats.taskSettled();
                    SHARED_TASKS.remove(key, created);
                });
                created.enqueue();
                return created;
            });
            // 共享任务可能由更远的驱动器先创建：被本驱动器采纳时立即提升到
            // 本驱动器（通常更靠近玩家）的优先级，避免近处区块卡在低优先级队列。
            task.raisePriority(ChunkSystemDriver.this.priority);
            this.pending.incrementAndGet();
            future.whenComplete((result, throwable) -> this.onUnitSettled());
            return task;
        });
        return this.futureFor(holder, status);
    }

    private void onUnitSettled() {
        if (this.pending.decrementAndGet() == 0 && this.claimReleased.compareAndSet(false, true)) {
            try {
                this.taskAware.prts$releaseClaim();
            } catch (Throwable t) {
                LOGGER.error("[chunk-system] releaseClaim failed at {} (dim={})",
                        this.center, this.dimension.location(), t);
            }
            ChunkSystemStats.taskCompleted(System.nanoTime() - this.submitNanos);
        }
    }

    /** 跨线程请求邻居 future；缺驱动者时延迟投递重调度。 */
    private CompletableFuture<ChunkResult<ChunkAccess>> futureFor(GenerationChunkHolder holder, ChunkStatus status) {
        final CompletableFuture<ChunkResult<ChunkAccess>> future =
                ((PRTSChunkSystemHolderAware) holder).prts$getOrCreateFuture(status);
        if (future.isDone()) {
            return future;
        }
        ChunkGenerationTask existing = ((PRTSChunkSystemHolderAware) holder).prts$task().get();
        if (existing == null || status.isAfter(existing.targetStatus)) {
            TaskKey key = new TaskKey(this.dimension, holder.getPos().x, holder.getPos().z, status.getIndex());
            // 共享任务已存在（驱动 future 的调度单元）→ 无需重调度
            if (!SHARED_TASKS.containsKey(key) && SHARED_DEFERRED.add(key)) {
                ChunkSystemStats.rescheduleDeferred();
                ((PRTSChunkMapRescheduleAware) this.chunkMap).prts$deferReschedule(holder, status);
            }
        } else {
            // 已有驱动器覆盖该状态：清去重位，允许未来再次按需 defer
            SHARED_DEFERRED.remove(new TaskKey(this.dimension, holder.getPos().x, holder.getPos().z, status.getIndex()));
        }
        return future;
    }

    /** 该状态在 GENERATION 金字塔下的读半径（0 = 不读邻居）。 */
    static int prts$readRadius(ChunkStatus status) {
        return Math.max(0, ChunkPyramid.GENERATION_PYRAMID.getStepTo(status).directDependencies().size() - 1);
    }

    /** 该坐标是否有覆盖 radius 邻域的缓存。 */
    private static boolean prts$covers(StaticCache2D<GenerationChunkHolder> cache, ChunkPos pos, int radius) {
        return cache.contains(pos.x - radius, pos.z - radius) && cache.contains(pos.x + radius, pos.z + radius);
    }

    /**
     * 本源驱动器能否安全驱动 (holder, status)：自己的锥域缓存覆盖该步，
     * 或该块自带（以本块为心）的 vanilla 任务缓存覆盖该步。
     */
    private boolean prts$canDrive(GenerationChunkHolder holder, ChunkStatus status) {
        int radius = prts$readRadius(status);
        ChunkPos pos = holder.getPos();
        if (prts$covers(this.cache, pos, radius)) {
            return true;
        }
        ChunkGenerationTask own = ((PRTSChunkSystemHolderAware) holder).prts$task().get();
        return own != null && prts$covers(((PRTSChunkSystemTaskAware) own).prts$cache(), pos, radius);
    }

    /** 按每步写半径生成锁令牌；features 半径取配置（默认 2，可降为 1 缓解前沿串行化）。 */
    private static ChunkSystemScheduler.ChunkLockToken[] tokensFor(ResourceKey<Level> dimension, ChunkPos pos, ChunkStatus status) {
        int radius = status == ChunkStatus.FEATURES
                ? Math.max(1, PRTSFeaturesConfig.chunkSystemSchedulerLockRadius)
                : status == ChunkStatus.STRUCTURE_STARTS ? 1 : 0;
        ChunkSystemScheduler.ChunkLockToken[] tokens =
                new ChunkSystemScheduler.ChunkLockToken[(2 * radius + 1) * (2 * radius + 1)];
        int i = 0;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                tokens[i++] = new ChunkSystemScheduler.ChunkLockToken(dimension,
                        new ChunkPos(pos.x + dx, pos.z + dz).toLong());
            }
        }
        java.util.Arrays.sort(tokens); // 全局有序：冲突在最早令牌暴露，减少抢锁重试的写入/回滚
        return tokens;
    }

    private record TaskKey(ResourceKey<Level> dimension, int x, int z, int statusIndex) {
    }

    /** 重调度请求被维度事件循环线程消费后清去重位，防止 SHARED_DEFERRED 无界增长。 */
    public static void deferredDrained(ResourceKey<Level> dimension, int x, int z, int statusIndex) {
        SHARED_DEFERRED.remove(new TaskKey(dimension, x, z, statusIndex));
    }

    /** 单区块×单状态任务：EMPTY 门、金字塔选择、依赖门、applyStep 四阶段。 */
    private final class StatusStepTask implements Task {

        private final GenerationChunkHolder holder;
        private final PRTSChunkSystemHolderAware holderAware;
        private final ChunkStatus status;
        private final CompletableFuture<ChunkResult<ChunkAccess>> future;
        private final ChunkSystemScheduler.ChunkLockToken[] lockTokens;
        private final AtomicInteger priority = new AtomicInteger(ChunkSystemDriver.this.priority);
        private final AtomicBoolean inQueued = new AtomicBoolean(false);
        /** 阶段状态（任务实例串行执行，无需 volatile）。 */
        private ChunkStep step;
        private boolean depsGated;
        private long suspendNanos;
        /** 门通过时的清除纪元（见 {@link ChunkSystemDriver#FUTURE_CLEAR_EPOCH}）。 */
        private long gatedEpoch;
        /** 无进展起点：首次挂起置位，完成任一 step 才清零；重评估不重置。 */
        private long parkEpisodeStartNanos;
        private long enqueuedAtNanos;
        private int parks;
        /** park 超时诊断只打一次。 */
        private final AtomicBoolean parkDiag = new AtomicBoolean(false);
        /** 上次看门狗重评估时刻（清扫线程按 watchdog 间隔限流）。 */
        private volatile long lastRedriveNanos;
        /** 当前挂起原因（诊断遥测，run 首行清除）。 */
        private String parkReason;
        /** 当前门对应的依赖 (holder, status)：看门狗据此补驱动，防"等在没有驱动者的 future 上"。 */
        private GenerationChunkHolder parkDep;
        private ChunkStatus parkDepStatus;

        StatusStepTask(GenerationChunkHolder holder, ChunkStatus status,
                       CompletableFuture<ChunkResult<ChunkAccess>> future) {
            this.holder = holder;
            this.holderAware = (PRTSChunkSystemHolderAware) holder;
            this.status = status;
            this.future = future;
            this.lockTokens = tokensFor(dimension, holder.getPos(), status);
            this.enqueuedAtNanos = System.nanoTime();
            // 本块正被主线程阻塞等待（且创建者不是紧急驱动器）：初始即最高档。
            // 只在建任务时定档，避免额外 raisePriority 并发改优先级（队列实现对该并发不保证）。
            if (this.priority.get() != 0 && MainThreadChunkWaits.isAwaited(dimension, holder.getPos())) {
                this.priority.set(0);
            }
        }

        /** 共享任务被更高优先级（更小数值）驱动器采纳时提升队列优先级。 */
        void raisePriority(int newPriority) {
            while (true) {
                int cur = this.priority.get();
                if (newPriority >= cur) {
                    return;
                }
                if (this.priority.compareAndSet(cur, newPriority)) {
                    ChunkSystemDriver.this.executor().notifyPriorityChange(this);
                    return;
                }
            }
        }

        void enqueue() {
            if (this.inQueued.compareAndSet(false, true)) {
                this.enqueuedAtNanos = System.nanoTime();
                ChunkSystemDriver.this.executor().schedule(this);
            }
        }

        private void park(CompletableFuture<?> gate, String reason) {
            this.park(gate, reason, null, null);
        }

        private void park(CompletableFuture<?> gate, String reason,
                          GenerationChunkHolder dep, ChunkStatus depStatus) {
            this.parkDep = dep;
            this.parkDepStatus = depStatus;
            this.suspendNanos = System.nanoTime();
            this.parks++;
            if (this.parkReason == null) {
                this.parkReason = reason;
                ChunkSystemStats.parkStart(reason);
            }
            // 无进展计时：首次挂起置位，只有真正完成一个 step 才清零（重评估不重置）。
            if (this.parkEpisodeStartNanos == 0L) {
                this.parkEpisodeStartNanos = System.nanoTime();
            }
            // 不再给每次挂起排定时任务：JFR 实测"每 park 两个 ScheduledFuture"让
            // ScheduledThreadPoolExecutor 的 DelayedWorkQueue 占 ~9% 采样。
            // 改由单条清扫线程周期扫描（见 sweepParked），代价与挂起数无关的常数级。
            gate.whenComplete((result, throwable) -> this.enqueue());
            ensureSweeper();
        }

        /** 清扫线程用：本任务所属驱动器（补驱动要给到同一个锥域）。 */
        ChunkSystemDriver driver() {
            return ChunkSystemDriver.this;
        }

        @Override
        public void run(Runnable releaseLocks) {
            this.inQueued.set(false);
            if (this.parkReason != null) {
                ChunkSystemStats.parkEnd(this.parkReason);
                this.parkReason = null;
            }
            long start = System.nanoTime();
            if (this.suspendNanos != 0) {
                ChunkSystemStats.depWait(this.status, start - this.suspendNanos);
                this.suspendNanos = 0;
            }
            try {
                if (this.future.isDone()) {
                    ChunkSystemStats.drained("done");
                    return; // 外部路径已结算（卸载失败 / replaceProtoChunk / 其它驱动器）
                }
                if (this.status != ChunkStatus.EMPTY) {
                    if (this.step == null) {
                        // 阶段 1+2：EMPTY 门 + 金字塔选择。EMPTY 同样必须"有驱动者"：
                        // futureFor 只取/建槽位，锥外分块（由门按需拉起的任务）的 EMPTY
                        // 无人完成 → 该任务 park 到看门狗兜底才恢复，故一律 ensureTask。
                        CompletableFuture<ChunkResult<ChunkAccess>> emptyFuture =
                                ChunkSystemDriver.this.ensureTask(this.holder, ChunkStatus.EMPTY);
                        if (!emptyFuture.isDone()) {
                            this.park(emptyFuture, "empty", this.holder, ChunkStatus.EMPTY);
                            return;
                        }
                        ChunkStatus persisted = this.holder.getPersistedStatus();
                        if (persisted == null) {
                            this.prts$drain("emptyFailed");
                            return; // EMPTY 失败（UNLOADED）：排空并结算，防依赖图挂起
                        }
                        boolean generate = this.status.isAfter(persisted);
                        if (generate) {
                            if (!decisionFuture.isDone()) {
                                this.park(decisionFuture, "decision");
                                return;
                            }
                            if (!needsGeneration) {
                                // 原版 scheduleChunkInLayer 在此抛 IllegalStateException；但 PRTS 是
                                // 多线程读 persisted，决策时的"整锥可从盘加载"可能已被并发改动
                                //（主线程换票/卸载与 worker 读并发）。异常会沿生成链传下去，被原版
                                // GenerationChunkHolder.applyStep 的 handle 标成 setFatalException → 崩服
                                //（2026-09-17 实测 crash-12.44.49）。改为自愈：升级为生成模式并补齐锥域。
                                ChunkSystemDriver.this.prts$escalateToGeneration();
                            }
                        }
                        this.step = (generate ? ChunkPyramid.GENERATION_PYRAMID : ChunkPyramid.LOADING_PYRAMID)
                                .getStepTo(this.status);
                    }
                    if (!this.depsGated) {
                        // 阶段 3：自身前序 + 邻块依赖门。门必须"有驱动者"：futureFor 只取/建 future 槽位，
                        // 无人完成时整条链永久 park（v04 实测三条终端状态），故三处门一律 ensureTask 建驱动；
                        // 建出的父/邻任务可能尚未物化父区块，执行前的二次校验见下方 prevMaterialize。
                        CompletableFuture<ChunkResult<ChunkAccess>> prevFuture =
                                ChunkSystemDriver.this.ensureTask(this.holder, this.status.getParent());
                        if (!prevFuture.isDone()) {
                            this.park(prevFuture, "prev", this.holder, this.status.getParent());
                            return;
                        }
                        ChunkDependencies deps = this.step.directDependencies();
                        List<CompletableFuture<?>> waiting = null;
                        for (int dist = 1; dist < deps.size(); dist++) {
                            ChunkStatus required = deps.get(dist);
                            ChunkPos pos = this.holder.getPos();
                            for (int dx = -dist; dx <= dist; dx++) {
                                for (int dz = -dist; dz <= dist; dz++) {
                                    if (Math.max(Math.abs(dx), Math.abs(dz)) != dist) {
                                        continue; // 只取切比雪夫环，内部距离已覆盖
                                    }
                                    ChunkPos npos = new ChunkPos(pos.x + dx, pos.z + dz);
                                    if (!cache.contains(npos.x, npos.z)) {
                                        // 锥域（acquire 半径）外：与 vanilla 读检查范围一致；
                                        // 越界 get 抛 IllegalArgumentException → 任务失败 → 中心排空 → FULL 永不完成
                                        continue;
                                    }
                                    CompletableFuture<ChunkResult<ChunkAccess>> neighborFuture =
                                            ChunkSystemDriver.this.ensureTask(cache.get(npos.x, npos.z), required);
                                    if (!neighborFuture.isDone()) {
                                        if (waiting == null) {
                                            waiting = new ArrayList<>();
                                        }
                                        waiting.add(neighborFuture);
                                    } else if (!prts$now(neighborFuture).isSuccess()) {
                                        // 邻居已判死（UNLOADED 哨兵/失败）：排空并结算，
                                        // 同原版 markForCancellation 后任务终止的语义
                                        this.prts$drain("neighborDead");
                                        return;
                                    }
                                }
                            }
                        }
                        if (waiting != null) {
                            ChunkSystemStats.gatedSuspend(waiting.size());
                            this.park(waiting.size() == 1 ? waiting.get(0)
                                    : CompletableFuture.allOf(waiting.toArray(new CompletableFuture<?>[0])), "deps");
                            return;
                        }
                        // FULL 前等待正在生成且未过 FEATURES 的邻居，
                        // 防邻居生成写与中央块 tick 队列竞争
                        if (this.status == ChunkStatus.FULL) {
                            List<CompletableFuture<?>> fullWait = null;
                            ChunkPos pos = this.holder.getPos();
                            for (int dx = -2; dx <= 2; dx++) {
                                for (int dz = -2; dz <= 2; dz++) {
                                    if (dx == 0 && dz == 0) {
                                        continue;
                                    }
                                    ChunkPos npos = new ChunkPos(pos.x + dx, pos.z + dz);
                                    if (!cache.contains(npos.x, npos.z)) {
                                        continue; // 锥域外邻居：无并发写风险
                                    }
                                    GenerationChunkHolder nh = cache.get(npos.x, npos.z);
                                    // 只等覆盖 FEATURES 的生成邻居；其余不会并发写本块
                                    PRTSChunkSystemHolderAware nhAware = (PRTSChunkSystemHolderAware) nh;
                                    ChunkGenerationTask ntask = nhAware.prts$task().get();
                                    if (ntask == null || ntask.targetStatus.isBefore(ChunkStatus.FEATURES)) {
                                        continue; // 不在生成锥域（纯加载/未加载/低目标）：无生成写风险
                                    }
                                    ChunkStatus persisted = nh.getPersistedStatus();
                                    if (persisted != null && !persisted.isBefore(ChunkStatus.FEATURES)) {
                                        continue; // 已过 FEATURES：无生成写入风险
                                    }
                                    CompletableFuture<ChunkResult<ChunkAccess>> nf =
                                            ChunkSystemDriver.this.ensureTask(nh, ChunkStatus.FEATURES);
                                    if (!nf.isDone()) {
                                        if (fullWait == null) {
                                            fullWait = new ArrayList<>();
                                        }
                                        fullWait.add(nf);
                                    } else {
                                        ChunkResult<ChunkAccess> nr = prts$now(nf);
                                        if (nr == null || !nr.isSuccess()) {
                                            // 邻居 FEATURES 已判死：不会写入，跳过等待
                                            continue;
                                        }
                                    }
                                }
                            }
                            if (fullWait != null) {
                                ChunkSystemStats.gatedSuspend(fullWait.size());
                                this.park(fullWait.size() == 1 ? fullWait.get(0)
                                        : CompletableFuture.allOf(fullWait.toArray(new CompletableFuture<?>[0])),
                                        "fullNeighbors");
                                return;
                            }
                        }
                        ChunkResult<ChunkAccess> prevResult = prts$now(prevFuture);
                        if (prevResult == null || !prevResult.isSuccess()) {
                            this.prts$drain("prevFailed");
                            return; // 前序失败：排空并结算（同 EMPTY 失败路径）
                        }
                        this.depsGated = true;
                        // 门通过时刻的清除纪元：之后只要没人清槽位，执行前就不必重扫全锥
                        this.gatedEpoch = FUTURE_CLEAR_EPOCH.get();
                    }
                }
                // 工作阶段（EMPTY 任务的 step 为 null，EMPTY 步两个金字塔同为恒等步，任取）
                ChunkStep workStep = this.step != null ? this.step
                        : ChunkPyramid.GENERATION_PYRAMID.getStepTo(ChunkStatus.EMPTY);
                // 执行前二次校验：applyStep（WorldGenRegion）直接读"父状态 + 依赖环各坐标状态"
                // 的 future 值，而门票级别变化会把槽位 failAndClear 清空，门通过之后仍可能失效
                //（v04 §8 竞态）；未物化时绝不调用 applyStep，否则要么 Parent chunk missing
                //（父块），要么 Requested chunk unavailable during world generation（依赖环）。
                // 门只在首次通过（depsGated 之后跳过），所以这层校验必须每次执行都做。
                // 执行缓存的选取：优先"以本块为中心的 vanilla 任务缓存"（原版不变式：
                // 任务缓存半径 = 该 target 的 EMPTY 累计半径 ≥ 本步读半径，故必然覆盖本步）。
                // 驱动器缓存以驱动器中心为心，锥内偏离中心的分块跑读半径大的步会
                // StaticCache2D 越界（生产 2026-09-17 实测 structure_references 越界 520+ 次
                // → 任务判死 → 生成链断裂 → 主线程永久等区块）。
                StaticCache2D<GenerationChunkHolder> stepCache = this.prts$stepCache(workStep);
                if (stepCache == null) {
                    // 无任何覆盖本步的缓存：请求以本块为中心的重调度，等它的缓存到位再跑
                    ChunkSystemDriver.this.futureFor(this.holder, this.status);
                    ChunkSystemStats.gatedSuspend(1);
                    this.park(STEP_CACHE_MISS_GATE, "stepCacheMiss", this.holder, this.status);
                    return;
                }
                if (this.status != ChunkStatus.EMPTY) {
                    // 父块校验很便宜（一次槽位读），每次执行都做
                    ChunkStatus missing = this.status.getParent();
                    if (this.holder.getChunkIfPresentUnchecked(missing) == null) {
                        ChunkSystemStats.gatedSuspend(1);
                        this.park(ChunkSystemDriver.this.ensureTask(this.holder, missing),
                                "prevMaterialize", this.holder, missing);
                        return;
                    }
                    // 依赖环物化校验是 O(环上坐标数) 的槽位读（JFR 实测占 ~12% 采样），
                    // 而它只在"门通过之后确实有槽位被清"时才有必要 —— 用全局清除纪元
                    // 把它降成常态一次 volatile 读；纪元变了才重扫（并刷新纪元）。
                    if (this.gatedEpoch != FUTURE_CLEAR_EPOCH.get()) {
                        missing = this.prts$firstMissingDependency(workStep, stepCache);
                        if (missing != null) {
                            this.gatedEpoch = FUTURE_CLEAR_EPOCH.get();
                            ChunkSystemStats.gatedSuspend(1);
                            this.park(ChunkSystemDriver.this.ensureTask(this.holder, missing),
                                    "depMaterialize", this.holder, missing);
                            return;
                        }
                        this.gatedEpoch = FUTURE_CLEAR_EPOCH.get();
                    }
                }
                // 返回外部哨兵（如 UNLOADED_CHUNK_FUTURE）= 状态不再被允许：排空，
                // 本 future 由原版失败清理机制（卸载/重新调度）结算
                try {
                    this.holderAware.prts$applyStep(workStep, chunkMap, stepCache);
                } catch (Throwable t) {
                    // 步内同步抛（典型：WorldGenRegion.getChunk 发现依赖在门/预检之后被清空或
                    // 被替换 → "Requested chunk unavailable during world generation"）。
                    // 原版会把它包成 ReportedException 并在主线程再抛一次；这里不判死：
                    // 重新走门（depsGated=false）+ 复核依赖环，缺谁就 park 等谁物化后重试。
                    // 复核后依赖齐全才按真失败向上抛。
                    ChunkStatus missing = this.prts$firstMissingDependency(workStep, stepCache);
                    if (missing != null) {
                        ChunkSystemStats.gatedSuspend(1);
                        this.depsGated = false;
                        this.park(ChunkSystemDriver.this.ensureTask(this.holder, missing),
                                "depRetry", this.holder, missing);
                        return;
                    }
                    throw t;
                }
                long execNanos = System.nanoTime() - start;
                long execMs = execNanos / 1_000_000L;
                if (execMs >= 500) {
                    LOGGER.warn("[chunk-system] slow step {} @ {} dim={} took {}ms (queueWait={}ms)",
                            this.status, this.holder.getPos(), dimension.location(), execMs,
                            (start - this.enqueuedAtNanos) / 1_000_000L);
                }
                ChunkSystemStats.executed(execNanos, start - this.enqueuedAtNanos);
                this.parkEpisodeStartNanos = 0L;   // 本步完成 = 有进展，重置无进展计时
            } finally {
                releaseLocks.run();
            }
        }

        /** 读依赖 future 的当前值：未完成/异常完成一律按"依赖已死"返回 null，绝不让异常逃出本任务。 */
        private static ChunkResult<ChunkAccess> prts$now(CompletableFuture<ChunkResult<ChunkAccess>> future) {
            try {
                return future.getNow(null);
            } catch (Throwable t) {
                return null;
            }
        }

        /**
         * 依赖环上第一个"未物化"的状态（null = 全部已物化）。与
         * {@code WorldGenRegion.getChunk} 的判据逐位对齐：按 {@code directDependencies}
         * 的每个距离取该距离要求的最高状态，读不到区块对象就是未物化。
         * 距离 0（本块自身）由调用方的父状态校验覆盖，这里从距离 1 起。
         */
        private ChunkStatus prts$firstMissingDependency(ChunkStep workStep,
                                                      StaticCache2D<GenerationChunkHolder> stepCache) {
            ChunkDependencies deps = workStep.directDependencies();
            ChunkPos pos = this.holder.getPos();
            for (int dist = 1; dist < deps.size(); dist++) {
                ChunkStatus required = deps.get(dist);
                if (required == null) {
                    continue;
                }
                for (int dx = -dist; dx <= dist; dx++) {
                    for (int dz = -dist; dz <= dist; dz++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != dist) {
                            continue; // 只取切比雪夫环
                        }
                        if (!stepCache.contains(pos.x + dx, pos.z + dz)) {
                            continue; // 锥域外：不会参与本步读取
                        }
                        GenerationChunkHolder neighbor = stepCache.get(pos.x + dx, pos.z + dz);
                        if (neighbor.getChunkIfPresentUnchecked(required) == null) {
                            return required;
                        }
                    }
                }
            }
            return null;
        }

        /**
         * 选取本步的 {@link StaticCache2D}：本块自己的 vanilla 任务缓存（以本块为心）优先，
         * 其次驱动器缓存（以驱动器中心为心，仅当覆盖本步）。返回 null 表示两者都不覆盖。
         */
        private StaticCache2D<GenerationChunkHolder> prts$stepCache(ChunkStep workStep) {
            int readRadius = Math.max(0, workStep.directDependencies().size() - 1);
            ChunkPos pos = this.holder.getPos();
            ChunkGenerationTask own = this.holderAware.prts$task().get();
            if (own != null) {
                StaticCache2D<GenerationChunkHolder> ownCache = ((PRTSChunkSystemTaskAware) own).prts$cache();
                if (ownCache != null && prts$covers(ownCache, pos, readRadius)) {
                    return ownCache;
                }
            }
            if (prts$covers(ChunkSystemDriver.this.cache, pos, readRadius)) {
                return ChunkSystemDriver.this.cache;
            }
            if (diagLogAllowed()) {
                LOGGER.warn("[chunk-system] step cache miss {} @ {} (dim={}) readRadius={} ownTask={} — requesting self-centered reschedule",
                        this.status, pos, dimension.location(), readRadius, own != null);
            }
            return null;
        }

        @Override
        public void propagateException(Throwable t) {
            ChunkSystemStats.exception();
            // 以"失败结果"正常完成而不是异常完成：依赖方 getNow 不会抛 CompletionException
            //（异常完成的 future 被 getNow 读到会 rethrow），也不会让原版
            // getChunkIfPresentUnchecked / applyStep 的 handle 把异常升级成 fatal 崩服。
            // 依赖方按既有的 !isSuccess() 分支排空，任务图与 pending 计数照常收敛。
            this.future.complete(ChunkResult.error("prts task failed: " + t));
            LOGGER.error("[chunk-system] status task failed: {} @ {} (dim={})",
                    this.status, this.holder.getPos(), dimension.location(), t);
        }

        /** 排空并结算自身 future，保证依赖图与 pending 计数能收敛。 */
        private void prts$drain(String reason) {
            ChunkSystemStats.drained(reason);
            if (!this.future.isDone()) {
                this.future.complete(ChunkResult.error("prts drained: " + reason));
            }
        }

        @Override
        public LockToken[] lockTokens() {
            return this.lockTokens;
        }

        @Override
        public int priority() {
            return this.priority.get();
        }

        @Override
        public String workLabel() {
            return "step " + this.status + " @ " + this.holder.getPos() + " dim=" + dimension.location();
        }
    }

    /**
     * 决策后才发现本锥确有区块需要生成（persisted 与决策依据不一致）：升级为生成模式，
     * 并按 GENERATION 金字塔补齐整个锥域的任务（幂等，重复调用只做一次）。
     * 替代原版 {@code scheduleChunkInLayer} 的 IllegalStateException —— 那条异常会沿
     * 生成链被原版 applyStep 的 handle 升级成 fatal 崩服。
     */
    private void prts$escalateToGeneration() {
        if (this.needsGeneration) {
            return;
        }
        synchronized (this) {
            if (this.needsGeneration) {
                return;
            }
            this.needsGeneration = true;
        }
        ChunkSystemStats.escalated();
        if (diagLogAllowed()) {
            LOGGER.warn("[chunk-system] escalate to generation @ {} target={} (dim={}): persisted advanced past the load decision",
                    this.center, this.target, this.dimension.location());
        }
        int coneRadius = ChunkPyramid.GENERATION_PYRAMID.getStepTo(this.target)
                .getAccumulatedRadiusOf(ChunkStatus.EMPTY);
        for (int dx = -coneRadius; dx <= coneRadius; dx++) {
            for (int dz = -coneRadius; dz <= coneRadius; dz++) {
                this.ensureTask(this.cache.get(this.center.x + dx, this.center.z + dz), ChunkStatus.EMPTY);
            }
        }
        for (ChunkStatus status : ChunkStatus.getStatusList()) {
            if (status == ChunkStatus.EMPTY || status.isAfter(this.target)) {
                continue;
            }
            int layerRadius = ChunkPyramid.GENERATION_PYRAMID.getStepTo(this.target)
                    .getAccumulatedRadiusOf(status);
            for (int dx = -layerRadius; dx <= layerRadius; dx++) {
                for (int dz = -layerRadius; dz <= layerRadius; dz++) {
                    this.ensureTask(this.cache.get(this.center.x + dx, this.center.z + dz), status);
                }
            }
        }
    }

    /** 单次清扫最多处理的任务数（代价有界）。 */
    private static final int SWEEP_LIMIT = 4096;

    /** 清扫线程只起一条。 */
    private static final AtomicBoolean SWEEPER_STARTED = new AtomicBoolean();

    private static void ensureSweeper() {
        if (SWEEPER_STARTED.compareAndSet(false, true)) {
            Thread thread = new Thread(() -> {
                while (true) {
                    try {
                        Thread.sleep(1000L);
                    } catch (InterruptedException e) {
                        return;
                    }
                    try {
                        sweepParked();
                    } catch (Throwable t) {
                        LOGGER.error("[chunk-system] park sweep failed", t);
                    }
                }
            }, "PRTS-ChunkSystem-Sweeper");
            thread.setDaemon(true);
            thread.start();
        }
    }

    /**
     * 周期清扫挂起任务：按间隔重评估 + 补驱动，超排空阈值则结算，首次满 10s 打一条诊断。
     * 取代"每次挂起排两个定时任务"的旧实现（JFR 实测后者占 ~9% 采样）。
     */
    private static void sweepParked() {
        long watchdogMs = PRTSFeaturesConfig.chunkSystemParkWatchdogMs;
        long drainMs = PRTSFeaturesConfig.chunkSystemParkDrainMs;
        if (watchdogMs <= 0L && drainMs <= 0L) {
            return;
        }
        long now = System.nanoTime();
        int scanned = 0;
        for (StatusStepTask task : SHARED_TASKS.values()) {
            if (++scanned > SWEEP_LIMIT) {
                return;
            }
            String reason = task.parkReason;
            long episodeStart = task.parkEpisodeStartNanos;
            if (reason == null || episodeStart == 0L || task.future.isDone()) {
                continue;
            }
            long parkedMs = (now - episodeStart) / 1_000_000L;
            if (drainMs > 0L && parkedMs >= drainMs) {
                ChunkSystemStats.parkWatchdogDrain();
                LOGGER.warn("[chunk-system] park watchdog drained {} @ {} (dim={}) after {}ms on {}",
                        task.status, task.holder.getPos(), task.driver().dimension.location(), parkedMs, reason);
                task.parkReason = null;
                task.parkEpisodeStartNanos = 0L;
                ChunkSystemStats.parkEnd(reason);
                task.prts$drain("parkTimeout");
                continue;
            }
            if (watchdogMs > 0L && parkedMs >= watchdogMs
                    && now - task.lastRedriveNanos >= watchdogMs * 1_000_000L) {
                task.lastRedriveNanos = now;
                ChunkSystemStats.parkWatchdogRedrive();
                if (diagLogAllowed()) {
                    LOGGER.warn("[chunk-system] park watchdog re-evaluating {} @ {} (dim={}) parked {}ms on {}",
                            task.status, task.holder.getPos(), task.driver().dimension.location(), parkedMs, reason);
                }
                // 门对应的依赖若还没有驱动者就补一个：仅重入队无法解决"无人完成的 future"
                if (task.parkDep != null && task.parkDepStatus != null) {
                    task.driver().ensureTask(task.parkDep, task.parkDepStatus);
                }
                task.enqueue();
            } else if (parkedMs >= 10_000L && task.parkDiag.compareAndSet(false, true) && diagLogAllowed()) {
                LOGGER.warn("[chunk-system] task parked {}/{} {}s @ {} (dim={}) futureDone={} inQueued={} parks={}",
                        task.status, reason, parkedMs / 1000L, task.holder.getPos(),
                        task.driver().dimension.location(), task.future.isDone(), task.inQueued.get(), task.parks);
            }
        }
    }

    private ExecutorManager executor() {
        return ChunkSystemScheduler.executor();
    }
}
