/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.mixin.optimization.general.chunksystem;

import io.izzel.arclight.common.optimization.general.chunksystem.guards.ChunkIoMainThreadQueue;
import io.izzel.arclight.common.optimization.general.chunksystem.guards.ChunkSystemMainThreadGuard;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BooleanSupplier;

/**
 * 主线程边界守卫（M2.2 四件套 ①）+ IO 延迟事件排空（四件套 ③ 出口）。
 *
 * <p>{@code ServerChunkCache.tick/save} 属维度级事务，仅允许属主线程
 * （服务器主线程 ∪ 维度并行下该维度的事件循环线程 ∪ region worker）；
 * chunk-system worker 只持写半径锁内的世界写权，触达此处即配置违约，
 * fail-fast 抛异常定位调用链（{@code chunk-system-fail-fast-guards}）。
 *
 * <p>{@code tickChunks} 尾部排空 IO 反序列化延迟事件队列：每轮维度
 * tick 结束即重放，事件重放线程 = 该维度的事件循环线程（与 Bukkit 桥
 * {@code callbackExecutor} 同属主，语义一致）。
 */
@Mixin(ServerChunkCache.class)
public abstract class ServerChunkCacheMixin_FailFast {

    @Shadow
    @Final
    ServerLevel level;

    @Inject(method = "tick", at = @At("HEAD"))
    private void prts$guardTick(BooleanSupplier hasTimeLeft, boolean tickEvenIfPaused, CallbackInfo ci) {
        ChunkSystemMainThreadGuard.checkTickOwner(this.level.getServer(), "ServerChunkCache.tick");
    }

    @Inject(method = "save", at = @At("HEAD"))
    private void prts$guardSave(boolean flush, CallbackInfo ci) {
        ChunkSystemMainThreadGuard.checkTickOwner(this.level.getServer(), "ServerChunkCache.save");
    }

    @Inject(method = "tickChunks", at = @At("RETURN"))
    private void prts$drainIoMainThreadQueue(CallbackInfo ci) {
        ChunkIoMainThreadQueue.drain();
    }
}
