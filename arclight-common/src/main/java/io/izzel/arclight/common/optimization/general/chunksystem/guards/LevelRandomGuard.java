/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.optimization.general.chunksystem.guards;

import io.izzel.arclight.common.compat.prts.PRTSFeaturesConfig;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.atomic.AtomicLong;

/**
 * {@code World.random} 跨线程检测装饰器（M2.2 主线程边界四件套 ④，
 * {@code worldgen-random-check} 配置驱动）。
 *
 * <p>原版 {@code Level.random} 是全局共享的 {@code LegacyRandomSource}
 * （AtomicLong CAS：无数据竞争但跨线程状态互串）；并行生成段内 mod/原版
 * feature 的随机消费在基线为单线程串行，多线程化后成为潜在行为漂移源。
 * 本装饰器默认 {@code warn}：非属主线程访问仅限流告警并照常回退委托
 * （先观测收集违规清单，灰度确认安全集合后由配置切 {@code throw}）。
 *
 * <p>属主集合与 {@link ChunkSystemMainThreadGuard#isTickOwner} 一致
 * （服务器主线程 ∪ 维度 tick 线程 ∪ region worker）；chunk-system worker
 * 与光照线程等属非属主访问（告警对象即「并行化新暴露的访问面」）。
 */
public final class LevelRandomGuard implements RandomSource {

    private static final Logger LOGGER = LogManager.getLogger("PRTS-ChunkSystem");
    private static final AtomicLong VIOLATIONS = new AtomicLong();
    private static volatile long lastLogNanos;

    private final RandomSource delegate;
    private final MinecraftServer server;

    public LevelRandomGuard(RandomSource delegate, MinecraftServer server) {
        this.delegate = delegate;
        this.server = server;
    }

    private void check() {
        String mode = PRTSFeaturesConfig.worldgenRandomCheck;
        if (!"throw".equals(mode) && !"warn".equals(mode)) {
            return; // off（或未知值）：直通
        }
        if (ChunkSystemMainThreadGuard.isTickOwner(this.server)) {
            return;
        }
        if ("throw".equals(mode)) {
            throw new IllegalStateException("[chunk-system] World.random accessed off tick-owner thread: "
                    + Thread.currentThread().getName());
        }
        long count = VIOLATIONS.incrementAndGet();
        long now = System.nanoTime();
        if (now - lastLogNanos > 5_000_000_000L) {
            lastLogNanos = now;
            LOGGER.warn("[chunk-system] World.random off-thread access (warn+fallback, total={}) from {}",
                    count, Thread.currentThread().getName());
        }
    }

    @Override
    public RandomSource fork() {
        return this.delegate.fork();
    }

    @Override
    public PositionalRandomFactory forkPositional() {
        return this.delegate.forkPositional();
    }

    @Override
    public void setSeed(long seed) {
        this.check();
        this.delegate.setSeed(seed);
    }

    @Override
    public int nextInt() {
        this.check();
        return this.delegate.nextInt();
    }

    @Override
    public int nextInt(int bound) {
        this.check();
        return this.delegate.nextInt(bound);
    }

    @Override
    public long nextLong() {
        this.check();
        return this.delegate.nextLong();
    }

    @Override
    public boolean nextBoolean() {
        this.check();
        return this.delegate.nextBoolean();
    }

    @Override
    public float nextFloat() {
        this.check();
        return this.delegate.nextFloat();
    }

    @Override
    public double nextDouble() {
        this.check();
        return this.delegate.nextDouble();
    }

    @Override
    public double nextGaussian() {
        this.check();
        return this.delegate.nextGaussian();
    }
}
