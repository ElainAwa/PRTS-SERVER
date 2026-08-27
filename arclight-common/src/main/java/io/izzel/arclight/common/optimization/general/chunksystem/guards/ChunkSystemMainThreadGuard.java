/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.optimization.general.chunksystem.guards;

import io.izzel.arclight.common.compat.prts.PRTSFeaturesConfig;
import io.izzel.arclight.common.optimization.general.servercore.DimensionTickManager;
import io.izzel.arclight.common.optimization.general.servercore.RegionTickManager;
import net.minecraft.server.MinecraftServer;

/**
 * 主线程边界 fail-fast 守卫（M2.2 主线程边界四件套 ①，C2ME 等价物）。
 *
 * <p>原版语义下区块管线的 tick/save 只允许服务器主线程执行；PRTS 的
 * 维度/区域并行把「维度 tick 属主」扩到 {@code PRTS-DimensionTick-N} 与
 * region worker，故本守卫的属主集合 = 服务器主线程 ∪ 维度 tick 线程 ∪
 * region worker。其余线程触达 {@code ServerChunkCache.tick/save} 即
 * fail-fast（配置 {@code chunk-system-fail-fast-guards} 关闭时仅放行）。
 *
 * <p>chunk-system worker（{@code PRTS-ChunkSystem-N}）不在属主集合内是
 * 故意的：生成步任务只持有写半径锁内的世界写权，不拥有维度级事务权。
 */
public final class ChunkSystemMainThreadGuard {

    private ChunkSystemMainThreadGuard() {
    }

    /** 当前线程是否该服务器任一维度的 tick 属主。 */
    public static boolean isTickOwner(MinecraftServer server) {
        return server.isSameThread()
                || DimensionTickManager.isDimensionTickThread()
                || RegionTickManager.isRegionWorker();
    }

    /** 当前线程是否 chunk-system 调度器 worker（线程名前缀识别）。 */
    public static boolean isChunkSystemWorker() {
        return Thread.currentThread().getName().startsWith("PRTS-ChunkSystem-");
    }

    /** tick/save 入口守卫：非属主线程抛异常（开启时）。 */
    public static void checkTickOwner(MinecraftServer server, String what) {
        if (PRTSFeaturesConfig.chunkSystemEnabled
                && PRTSFeaturesConfig.chunkSystemFailFastGuards
                && !isTickOwner(server)) {
            throw new IllegalStateException("[chunk-system] " + what
                    + " called off tick-owner thread: " + Thread.currentThread().getName());
        }
    }
}
