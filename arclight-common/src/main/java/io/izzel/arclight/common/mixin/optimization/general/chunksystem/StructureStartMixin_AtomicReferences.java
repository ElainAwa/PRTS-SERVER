/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.mixin.optimization.general.chunksystem;

import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * worldgen 线程安全包（M3，照搬清单 1）：{@code StructureStart.references} 原子化。
 *
 * <p>原版 references 是普通 int：STRUCTURE_REFERENCES 步骤里邻块对同一
 * {@code StructureStart} 调 {@code addReference()}。M2 细粒度并行下，相距
 * 超过写半径锁域的多个块可同时推进该步，对同一 start 并发自增构成数据竞争
 * （丢失计数 → start 被误判可再引用/过早失效）。改用 {@link AtomicInteger}，
 * 自增后同步写回原字段（{@code createTag} 直读字段而非 getter）。
 *
 * <p>不能用 {@code @ModifyArg} 拦 {@code createTag} 的 {@code putInt}：该方法内
 * 共 3 处 putInt（ChunkX/ChunkZ/references），无差别拦截会把块坐标也改写成
 * refs 值（M3 回归轮实测 ChunkX/ChunkZ 归零回归，已回退该写法）。
 */
@Mixin(StructureStart.class)
public abstract class StructureStartMixin_AtomicReferences {

    @Shadow
    private int references;

    @Unique
    private final AtomicInteger prts$refCount = new AtomicInteger();

    @Inject(method = "<init>", at = @At("RETURN"))
    private void prts$initRefCount(Structure structure, ChunkPos chunkPos, int refs, PiecesContainer pieces, CallbackInfo ci) {
        this.prts$refCount.set(this.references);
    }

    @Shadow
    protected abstract int getMaxReferences();

    @Inject(method = "canBeReferenced", at = @At("HEAD"), cancellable = true)
    private void prts$canBeReferenced(CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(this.prts$refCount.get() < this.getMaxReferences());
    }

    @Inject(method = "addReference", at = @At("HEAD"), cancellable = true)
    private void prts$addReference(CallbackInfo ci) {
        // 原子计数为权威值；自增后同步写回原字段供 createTag 直读。
        // 并发自增下字段瞬时可能滞后于原子值，但每次写入都来自原子自增结果，
        // 最终收敛于原子值；序列化在主线程、生成完成后进行，口径与原版一致。
        this.references = this.prts$refCount.incrementAndGet();
        ci.cancel();
    }

    @Inject(method = "getReferences", at = @At("HEAD"), cancellable = true)
    private void prts$getReferences(CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(this.prts$refCount.get());
    }
}
