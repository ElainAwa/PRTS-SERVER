/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.mixin.optimization.general.chunksystem;

import io.izzel.arclight.common.compat.prts.PRTSFeaturesConfig;
import io.izzel.arclight.common.optimization.general.chunksystem.ChunkSystemScheduler;
import io.izzel.arclight.common.optimization.general.chunksystem.guards.ChunkIoMainThreadQueue;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.thread.BlockableEventLoop;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkType;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * IO 线程模型（M2.2）：把 {@code scheduleChunkLoad} 的反序列化段
 * （{@link ChunkSerializer#read}）从 {@code mainThreadExecutor} 移到
 * chunk-system 反序列化专用单线程执行。
 *
 * <p>线程安全性依据（1.21.1 反编译实读 + 实测）：
 * <ul>
 *   <li>磁盘读（{@code readChunk} → IOWorker）本就异步（原版每存储一个
 *       IO 线程，NBT 解压在该线程）；</li>
 *   <li>反序列化段串行在专用单线程：{@code PoiManager
 *       .checkConsistencyWithBlocks} 经 {@code SectionStorage.getOrLoad}
 *       写共享非并发 Map，并行反序列化会撞崩（实测 AIOOBE）；</li>
 *   <li>反序列化触达的共享缓存：结构模板库为
 *       {@code ConcurrentHashMap}（{@code StructureTemplateManager
 *       .structureRepository}），线程安全；结构 start 解包只读注册表；</li>
 *   <li>{@code markPosition}（{@code chunkTypeCache}，Long2ByteMap 非并发）
 *       保留在主线程完成链（{@code thenApplyAsync(mainThreadExecutor)}），
 *       时序与原版一致（EMPTY future 结算前已写入）；空块分支的
 *       {@code createEmptyChunk}（内部同样写 {@code chunkTypeCache}）同理回主线程；</li>
 *   <li>反序列化内发射的 {@code ChunkDataEvent.Load} 经
 *       {@link ChunkIoMainThreadQueue} 捕获并延迟主线程重放（四件套 ③）；</li>
 *   <li>失败路径（{@code handleChunkLoadFailure}）保留
 *       {@code exceptionallyAsync(mainThreadExecutor)} 语义。</li>
 * </ul>
 *
 * <p>与 ChunkJournal（reliable-chunk-save）兼容：journal 序列化走主线程
 * {@code ChunkSerializer.write}，经 full-chunk future 取脏块，与本改动正交。
 *
 * <p>门控：{@code chunk-system-enabled && chunk-async-io-enabled} 且调度器
 * 已启用；任一不满足走原版主线程反序列化路径逐位一致。
 */
@Mixin(ChunkMap.class)
public abstract class ChunkMapMixin_IoThreadModel {

    private static final Logger LOGGER = LogManager.getLogger("PRTS-ChunkSystem");

    @Shadow
    @Final
    ServerLevel level;

    @Shadow
    @Final
    private PoiManager poiManager;

    @Shadow
    @Final
    private BlockableEventLoop<Runnable> mainThreadExecutor;

    @Shadow
    private CompletableFuture<Optional<CompoundTag>> readChunk(ChunkPos pos) {
        return null;
    }

    @Shadow
    private ChunkAccess createEmptyChunk(ChunkPos pos) {
        return null;
    }

    @Shadow
    private ChunkAccess handleChunkLoadFailure(Throwable throwable, ChunkPos pos) {
        return null;
    }

    @Shadow
    private byte markPosition(ChunkPos pos, ChunkType type) {
        return 0;
    }

    // 注：storageInfo() 声明在父类 ChunkStorage，不能在此 shadow（构建期注解重映射按目标类解析，
    // 继承成员解析失败→运行期 InvalidMixinException），改经 ChunkStorageAccessor_ChunkSystem。

    @Inject(method = "scheduleChunkLoad", at = @At("HEAD"), cancellable = true)
    private void prts$deserializeOffMainThread(ChunkPos pos, CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        if (!PRTSFeaturesConfig.chunkSystemEnabled || !PRTSFeaturesConfig.chunkAsyncIoEnabled) {
            return;
        }
        Executor ioExecutor = ChunkSystemScheduler.ioDeserializeExecutor();
        if (ioExecutor == null) {
            return;
        }
        CompletableFuture<ChunkAccess> future = this.readChunk(pos)
                .thenApply(optional -> optional.filter(tag -> {
                    if (!tag.contains("Status", 8)) {
                        LOGGER.error("Chunk file at {} is missing level data, skipping", pos);
                        return false;
                    }
                    return true;
                }))
                .thenCompose(optional -> {
                    if (optional.isEmpty()) {
                        // 空块分支（磁盘无文件）：createEmptyChunk→markPositionReplaceable 写
                        // chunkTypeCache（Long2ByteOpenHashMap 非并发），与主线程卸载路径的
                        // remove 并发会 rehash AIOOBE（M3 对照轮实测崩溃）。原版该步在
                        // thenApplyAsync(mainThreadExecutor) 内，故此处同样回主线程执行。
                        return CompletableFuture.supplyAsync(() -> this.createEmptyChunk(pos),
                                this.mainThreadExecutor);
                    }
                    CompoundTag tag = optional.get();
                    CompletableFuture<ChunkAccess> deserialized = new CompletableFuture<>();
                    try {
                        ioExecutor.execute(() -> {
                            ChunkIoMainThreadQueue.beginCapture();
                            try {
                                this.level.getProfiler().incrementCounter("chunkLoad");
                                deserialized.complete(ChunkSerializer.read(
                                        this.level, this.poiManager,
                                        ((ChunkStorageAccessor_ChunkSystem) this).prts$storageInfo(), pos, tag));
                            } catch (Throwable t) {
                                deserialized.completeExceptionally(t);
                            } finally {
                                ChunkIoMainThreadQueue.endCapture();
                            }
                        });
                    } catch (Throwable t) {
                        deserialized.completeExceptionally(t);
                    }
                    // chunkTypeCache 非并发容器：写回留在主线程完成链（时序与原版一致）
                    return deserialized.thenApplyAsync(chunk -> {
                        this.markPosition(pos, chunk.getPersistedStatus().getChunkType());
                        return chunk;
                    }, this.mainThreadExecutor);
                })
                .exceptionallyAsync(throwable -> this.handleChunkLoadFailure(throwable, pos), this.mainThreadExecutor);
        cir.setReturnValue(future);
    }
}
