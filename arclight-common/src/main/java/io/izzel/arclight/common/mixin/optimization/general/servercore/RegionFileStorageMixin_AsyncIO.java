/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 *
 * This file adapts code from ServerCore by Wesley1808
 * (https://github.com/Wesley1808/ServerCore), licensed under GPL-3.0.
 * Original code Copyright (c) Wesley1808.
 */

package io.izzel.arclight.common.mixin.optimization.general.servercore;

import io.izzel.arclight.common.optimization.general.servercore.ServerCoreConfig;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.DataInputStream;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 实验性异步区块 IO（features.async-chunk-io，默认开）。
 * RegionFileStorage.read 提交专用线程池，行为不变仅转移 IO/CPU 开销。池内重入同步执行防死锁。
 */
@Mixin(RegionFileStorage.class)
public abstract class RegionFileStorageMixin_AsyncIO {

    @Shadow protected abstract RegionFile getRegionFile(ChunkPos pos) throws IOException;

    private static final ThreadLocal<Boolean> IN_POOL = ThreadLocal.withInitial(() -> false);
    private static ExecutorService POOL;

    private static ExecutorService pool() {
        if (POOL == null) {
            int n = Math.max(2, Runtime.getRuntime().availableProcessors() / 2);
            POOL = new ThreadPoolExecutor(n, n, 60L, TimeUnit.SECONDS,
                    new LinkedBlockingQueue<>(),
                    r -> {
                        Thread t = new Thread(r, "PRTS-ChunkIO");
                        t.setDaemon(true);
                        return t;
                    });
        }
        return POOL;
    }

    @Inject(method = "read", at = @At("HEAD"), cancellable = true)
    private void luminara$asyncRead(ChunkPos pos, CallbackInfoReturnable<CompoundTag> cir) throws IOException {
        if (!ServerCoreConfig.features().asyncChunkIoEnabled()) {
            return;
        }
        if (IN_POOL.get()) {
            return;
        }
        try {
            CompoundTag result = pool().submit(() -> {
                IN_POOL.set(true);
                try {
                    RegionFile rf = getRegionFile(pos);
                    try (DataInputStream in = rf.getChunkDataInputStream(pos)) {
                        return in == null ? null : NbtIo.read(in);
                    }
                } finally {
                    IN_POOL.set(false);
                }
            }).get();
            cir.setReturnValue(result);
            cir.cancel();
        } catch (Exception e) {
            // 失败则回退到原始同步读取（不 cancel）
        }
    }
}
