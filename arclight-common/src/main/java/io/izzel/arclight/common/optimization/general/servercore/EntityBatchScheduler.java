/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.optimization.general.servercore;

import io.izzel.arclight.common.compat.prts.PRTSFeaturesConfig;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 实体批并行:region worker 实体阶段内把可并行实体收集成批,阶段末提交到独立子任务池
 * 并行 tick(region worker 收口等待)。过滤规则:minecraft 命名空间生物且未被路由主线程;
 * allow 白名单可显式放行 modded 类,deny 黑名单优先级更高;玩家/拾取交互实体恒串行。
 * 子任务线程复用发起 region 的 REGION_CONTEXT(跨区写/计划刻/实体新增走既有 worker 路径)。
 */
public final class EntityBatchScheduler {

    private static final Logger LOGGER = LogManager.getLogger("Arclight");

    private static volatile ThreadPoolExecutor pool;
    private static final Set<String> ALLOW = ConcurrentHashMap.newKeySet();
    private static final Set<String> DENY = ConcurrentHashMap.newKeySet();
    private static final Set<String> TYPE_CACHE = ConcurrentHashMap.newKeySet();
    private static final Set<String> TYPE_CACHE_NEG = ConcurrentHashMap.newKeySet();

    private EntityBatchScheduler() {
    }

    /** 配置载入时更新过滤名单并清缓存。 */
    public static void configure(List<String> allow, List<String> deny) {
        ALLOW.clear();
        DENY.clear();
        TYPE_CACHE.clear();
        TYPE_CACHE_NEG.clear();
        ALLOW.addAll(allow);
        DENY.addAll(deny);
    }

    private static ThreadPoolExecutor pool() {
        ThreadPoolExecutor p = pool;
        if (p == null || p.isShutdown()) {
            synchronized (EntityBatchScheduler.class) {
                p = pool;
                if (p == null || p.isShutdown()) {
                    int threads = PRTSFeaturesConfig.entityBatchThreads > 0
                            ? PRTSFeaturesConfig.entityBatchThreads
                            : Math.max(2, Runtime.getRuntime().availableProcessors() - RegionTickManager.regionCount());
                    p = new ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS,
                            new LinkedBlockingQueue<>(), r -> {
                        Thread t = new Thread(null, r, "PRTS-EntityBatch", 8L * 1024 * 1024);
                        t.setDaemon(true);
                        return t;
                    });
                    pool = p;
                }
            }
        }
        return p;
    }

    /** 实体是否可批并行(不可批的由调用方走原串行路径)。 */
    public static boolean acceptable(ServerLevel level, Entity entity) {
        if (!PRTSFeaturesConfig.entityBatchParallel) {
            return false;
        }
        if (entity instanceof Player || entity instanceof ItemEntity || entity instanceof ExperienceOrb
                || !(entity instanceof LivingEntity)) {
            return false;
        }
        if (RegionTickManager.needsMainThreadTick(entity)) {
            return false;
        }
        String key = net.minecraft.world.entity.EntityType.getKey(entity.getType()).toString();
        if (DENY.contains(key)) {
            return false;
        }
        Boolean cached = TYPE_CACHE.contains(key) ? Boolean.TRUE
                : (TYPE_CACHE_NEG.contains(key) ? Boolean.FALSE : null);
        if (cached != null) {
            return cached;
        }
        boolean ok;
        if (!ALLOW.isEmpty()) {
            ok = matches(key, ALLOW);
        } else {
            ok = key.startsWith("minecraft:");
        }
        (ok ? TYPE_CACHE : TYPE_CACHE_NEG).add(key);
        return ok;
    }

    private static boolean matches(String key, Set<String> patterns) {
        for (String p : patterns) {
            if (p.endsWith("*")) {
                if (key.startsWith(p.substring(0, p.length() - 1))) {
                    return true;
                }
            } else if (key.equals(p)) {
                return true;
            }
        }
        return false;
    }

    /** 一批待并行实体(region worker 收集,阶段末提交)。 */
    public static final class Batch {
        private final ServerLevel level;
        private final int region;
        private final AtomicBoolean degradeStop;
        private final List<Entity> entities = new ArrayList<>();

        Batch(ServerLevel level, int region, AtomicBoolean degradeStop) {
            this.level = level;
            this.region = region;
            this.degradeStop = degradeStop;
        }

        public boolean add(Entity entity) {
            entities.add(entity);
            return true;
        }

        /** 提交并行执行并等待完成(超时置停止门触发软降级)。 */
        public void flush() {
            if (entities.isEmpty()) {
                return;
            }
            int poolSize = pool().getCorePoolSize();
            int chunkSize = Math.max(1, (entities.size() + poolSize - 1) / poolSize);
            CountDownLatch latch = new CountDownLatch((entities.size() + chunkSize - 1) / chunkSize);
            for (int i = 0; i < entities.size(); i += chunkSize) {
                int end = Math.min(i + chunkSize, entities.size());
                List<Entity> chunk = new ArrayList<>(entities.subList(i, end));
                pool().execute(() -> runChunk(chunk, latch));
            }
            entities.clear();
            try {
                if (!latch.await(10L, TimeUnit.SECONDS)) {
                    LOGGER.warn("[entity-batch] subtask barrier timeout, triggering soft degrade");
                    degradeStop.set(true);
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                degradeStop.set(true);
            }
        }

        private void runChunk(List<Entity> chunk, CountDownLatch latch) {
            RegionTickManager.enterRegionContext(level, region, degradeStop);
            try {
                for (Entity entity : chunk) {
                    if (degradeStop.get() || entity.isRemoved()) {
                        continue;
                    }
                    try {
                        RegionTickManager.tickEntityInContext(level, entity);
                    } catch (Throwable t) {
                        // 单实体异常隔离,不影响同批其他实体。
                        LOGGER.error("[entity-batch] entity {} tick failed: {}", entity, t.toString());
                    }
                }
            } finally {
                RegionTickManager.exitRegionContext();
                latch.countDown();
            }
        }
    }

    /** region worker 实体阶段入口:开启批收集(启用且未熔断时)。 */
    public static Batch begin(ServerLevel level, int region, AtomicBoolean degradeStop) {
        if (!PRTSFeaturesConfig.entityBatchParallel) {
            return null;
        }
        return new Batch(level, region, degradeStop);
    }
}
