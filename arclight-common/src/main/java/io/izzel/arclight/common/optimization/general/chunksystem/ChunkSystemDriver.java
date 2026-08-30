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

/** 单区块×单状态任务图驱动器：细粒度调度生成步骤，依赖与锁按写半径展开。 */
public final class ChunkSystemDriver {

    private static final Logger LOGGER = LogManager.getLogger("PRTS-ChunkSystem");

    /** park 挂起超时诊断的共享调度器（守护线程，仅诊断用）。 */
    private static final ScheduledExecutorService PARK_DIAG =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "PRTS-ChunkSystem-ParkDiag");
                thread.setDaemon(true);
                return thread;
            });

    /** 全局共享驱动任务表：跨驱动器去重，任务对象唯一。 */
    private static final ConcurrentHashMap<TaskKey, StatusStepTask> SHARED_TASKS = new ConcurrentHashMap<>();

    /** 全局重调度请求去重：防多个驱动器对未来重复投递 reschedule（跨驱动器去重）。 */
    private static final ConcurrentHashMap.KeySetView<TaskKey, Boolean> SHARED_DEFERRED =
            ConcurrentHashMap.newKeySet();

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
        private long enqueuedAtNanos;
        private int parks;
        /** park 超时诊断只打一次。 */
        private final AtomicBoolean parkDiag = new AtomicBoolean(false);
        /** 当前挂起原因（诊断遥测，run 首行清除）。 */
        private String parkReason;

        StatusStepTask(GenerationChunkHolder holder, ChunkStatus status,
                       CompletableFuture<ChunkResult<ChunkAccess>> future) {
            this.holder = holder;
            this.holderAware = (PRTSChunkSystemHolderAware) holder;
            this.status = status;
            this.future = future;
            this.lockTokens = tokensFor(dimension, holder.getPos(), status);
            this.enqueuedAtNanos = System.nanoTime();
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
            this.suspendNanos = System.nanoTime();
            this.parks++;
            if (this.parkReason == null) {
                this.parkReason = reason;
                ChunkSystemStats.parkStart(reason);
            }
            // 挂起 10s 打一条诊断，暴露卡住的门
            if (this.parkDiag.compareAndSet(false, true)) {
                final long parkAt = System.nanoTime();
                PARK_DIAG.schedule(() -> {
                    if (this.parkReason != null && reason.equals(this.parkReason)) {
                        LOGGER.warn("[chunk-system] task parked {}/{} {}s @ {} (dim={}) futureDone={} inQueued={} parks={}",
                                this.status, reason, (System.nanoTime() - parkAt) / 1_000_000_000L,
                                this.holder.getPos(), dimension.location(),
                                this.future.isDone(), this.inQueued.get(), this.parks);
                    }
                }, 10, TimeUnit.SECONDS);
            }
            gate.whenComplete((result, throwable) -> this.enqueue());
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
                        // 阶段 1+2：EMPTY 门 + 金字塔选择
                        CompletableFuture<ChunkResult<ChunkAccess>> emptyFuture =
                                ChunkSystemDriver.this.futureFor(this.holder, ChunkStatus.EMPTY);
                        if (!emptyFuture.isDone()) {
                            this.park(emptyFuture, "empty");
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
                                // 原版 scheduleChunkInLayer 同语义不变量
                                throw new IllegalStateException(
                                        "Can't load chunk " + this.holder.getPos() + " to " + this.status
                                                + ", but didn't expect to need to generate");
                            }
                        }
                        this.step = (generate ? ChunkPyramid.GENERATION_PYRAMID : ChunkPyramid.LOADING_PYRAMID)
                                .getStepTo(this.status);
                    }
                    if (!this.depsGated) {
                        // 阶段 3：自身前序 + 邻块依赖门
                        CompletableFuture<ChunkResult<ChunkAccess>> prevFuture =
                                ChunkSystemDriver.this.futureFor(this.holder, this.status.getParent());
                        if (!prevFuture.isDone()) {
                            this.park(prevFuture, "prev");
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
                                            ChunkSystemDriver.this.futureFor(
                                                    cache.get(npos.x, npos.z), required);
                                    if (!neighborFuture.isDone()) {
                                        if (waiting == null) {
                                            waiting = new ArrayList<>();
                                        }
                                        waiting.add(neighborFuture);
                                    } else if (!neighborFuture.getNow(null).isSuccess()) {
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
                                            ChunkSystemDriver.this.futureFor(nh, ChunkStatus.FEATURES);
                                    if (!nf.isDone()) {
                                        if (fullWait == null) {
                                            fullWait = new ArrayList<>();
                                        }
                                        fullWait.add(nf);
                                    } else {
                                        ChunkResult<ChunkAccess> nr = nf.getNow(null);
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
                        ChunkResult<ChunkAccess> prevResult = prevFuture.getNow(null);
                        if (prevResult == null || !prevResult.isSuccess()) {
                            this.prts$drain("prevFailed");
                            return; // 前序失败：排空并结算（同 EMPTY 失败路径）
                        }
                        this.depsGated = true;
                    }
                }
                // 工作阶段（EMPTY 任务的 step 为 null，EMPTY 步两个金字塔同为恒等步，任取）
                ChunkStep workStep = this.step != null ? this.step
                        : ChunkPyramid.GENERATION_PYRAMID.getStepTo(ChunkStatus.EMPTY);
                // 返回外部哨兵（如 UNLOADED_CHUNK_FUTURE）= 状态不再被允许：排空，
                // 本 future 由原版失败清理机制（卸载/重新调度）结算
                this.holderAware.prts$applyStep(workStep, chunkMap, cache);
                long execNanos = System.nanoTime() - start;
                long execMs = execNanos / 1_000_000L;
                if (execMs >= 500) {
                    LOGGER.warn("[chunk-system] slow step {} @ {} dim={} took {}ms (queueWait={}ms)",
                            this.status, this.holder.getPos(), dimension.location(), execMs,
                            (start - this.enqueuedAtNanos) / 1_000_000L);
                }
                ChunkSystemStats.executed(execNanos, start - this.enqueuedAtNanos);
            } finally {
                releaseLocks.run();
            }
        }

        @Override
        public void propagateException(Throwable t) {
            ChunkSystemStats.exception();
            // 让本 future 异常完成，依赖它的任务图得以排空（不卡死计数）
            this.future.completeExceptionally(t);
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

    private ExecutorManager executor() {
        return ChunkSystemScheduler.executor();
    }
}
