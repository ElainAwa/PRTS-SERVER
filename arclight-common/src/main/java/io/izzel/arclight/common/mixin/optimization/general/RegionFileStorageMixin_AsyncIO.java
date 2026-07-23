package io.izzel.arclight.common.mixin.optimization.general;

import io.izzel.arclight.i18n.ArclightConfig;
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
 * 实验性异步区块 IO（默认关闭，由总开关罩住）。
 *
 * 保守实现：将 RegionFileStorage.read（磁盘读取 + NBT 解析）整体提交到专用线程池，
 * 调用线程（通常是主线程）通过 Future.get() 等待结果。数据与原逻辑完全一致，
 * 因此不改变任何游戏行为；仅把磁盘 IO / 解析的 CPU 占用从调用线程移到线程池。
 *
 * 说明：此保守版不会消除"调用线程等待结果"的延迟（主线程仍会等）。要真正消除
 * 区块加载/传送卡顿，需要把整个区块加载管线改为 Future 化（Paper 风格），那是
 * 高风险改动，建议作为专门的分阶段实现，不在本实验性开关内。
 * 若 read 在池线程中被重入调用，直接同步执行以避免死锁。
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
                        Thread t = new Thread(r, "Luminara-ChunkIO");
                        t.setDaemon(true);
                        return t;
                    });
        }
        return POOL;
    }

    @Inject(method = "read", at = @At("HEAD"), cancellable = true)
    private void luminara$asyncRead(ChunkPos pos, CallbackInfoReturnable<CompoundTag> cir) throws IOException {
        if (!ArclightConfig.spec().getOptimization().isExperimentalOptimizationsEnabled()) {
            return;
        }
        if (!ArclightConfig.spec().getOptimization().getChunkOptimization().isAsyncChunkIoEnabled()) {
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
