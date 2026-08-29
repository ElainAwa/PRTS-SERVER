/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.mixin.optimization.general.servercore.region_parallel;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.izzel.arclight.common.optimization.general.servercore.DimensionTickManager;
import io.izzel.arclight.common.optimization.general.servercore.RegionTickManager;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;

/**
 * worker 线程（维度/区域）上把 onRemove 延迟到主线程执行：流体 setBlock 触发
 * onRemove 时 Minecolonies 兼容钩子会跨线程读 BE。minecolonies_compatibility
 * 用 @WrapMethod 包了 onRemove（handler 直接执行绕过 @Inject 注入体），故本
 * mixin 也用 @WrapMethod 且优先级更高（1500 > 1000），包装在最外层先短路。
 */
@Mixin(value = BlockBehaviour.class, priority = 1500)
public abstract class BlockBehaviourMixin_OnRemoveDefer {

    @WrapMethod(method = "onRemove")
    private void arclight$deferWorkerOnRemove(BlockState state, Level level, BlockPos pos,
                                              BlockState newState, boolean movedByPiston,
                                              Operation<Void> original) {
        if (DimensionTickManager.isDimensionTickThread() || RegionTickManager.isRegionWorker()) {
            RegionTickManager.queueMainThreadBlockRemoval(() -> {
                try {
                    original.call(state, level, pos, newState, movedByPiston);
                } catch (Throwable t) {
                    org.apache.logging.log4j.LogManager.getLogger("PRTS-ThreadPolicy")
                            .error("[thread-policy] deferred onRemove failed at {}: {}", pos, t.toString());
                }
            });
        } else {
            original.call(state, level, pos, newState, movedByPiston);
        }
    }
}
