/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.mixin.optimization.general.servercore.region_parallel;

import io.izzel.arclight.common.optimization.general.servercore.DimensionTickManager;
import io.izzel.arclight.common.optimization.general.servercore.RegionTickManager;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * worker 线程（维度/区域）上把 onRemove 延迟到主线程执行：
 * 流体 setBlock 触发 onRemove 时，Minecolonies 兼容钩子会跨线程读 BE，
 * 排主线程保语义且消除违规（刷石机每 tick 触发，生产 785k 违规来源）。
 */
// 优先级 1500（默认 1000）：HEAD 注入排在 minecolonies_compatibility 的
// onRemove 钩子之前，worker 上先 cancel 才能挡住它跨线程 getBlockEntity。
@Mixin(value = BlockBehaviour.class, priority = 1500)
public abstract class BlockBehaviourMixin_OnRemoveDefer {

    @Shadow
    protected abstract void onRemove(BlockState state, Level level, BlockPos pos,
                                     BlockState newState, boolean movedByPiston);

    @Inject(method = "onRemove", at = @At("HEAD"), cancellable = true)
    private void arclight$deferWorkerOnRemove(BlockState state, Level level, BlockPos pos,
                                              BlockState newState, boolean movedByPiston, CallbackInfo ci) {
        if (DimensionTickManager.isDimensionTickThread() || RegionTickManager.isRegionWorker()) {
            RegionTickManager.queueMainThreadBlockRemoval(() -> {
                try {
                    this.onRemove(state, level, pos, newState, movedByPiston);
                } catch (Throwable t) {
                    org.apache.logging.log4j.LogManager.getLogger("PRTS-ThreadPolicy")
                            .error("[thread-policy] deferred onRemove failed at {}: {}", pos, t.toString());
                }
            });
            ci.cancel();
        }
    }
}
