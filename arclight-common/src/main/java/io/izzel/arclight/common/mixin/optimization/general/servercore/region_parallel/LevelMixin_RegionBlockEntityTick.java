/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.mixin.optimization.general.servercore.region_parallel;

import io.izzel.arclight.common.compat.prts.PRTSFeaturesConfig;
import io.izzel.arclight.common.optimization.general.servercore.DimensionTickManager;
import io.izzel.arclight.common.optimization.general.servercore.RegionTickManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 方块实体 tick 的调度入口。并行默认关闭（BE 间交互复杂，跨区访问有竞态）：
 * 并行开启时进区域队列，否则维度并行期排队到主线程、其余情况原版内联 tick。
 */
@Mixin(Level.class)
public abstract class LevelMixin_RegionBlockEntityTick {

    @Redirect(method = "tickBlockEntities",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/entity/TickingBlockEntity;tick()V"))
    private void arclight$regionBlockEntityTick(TickingBlockEntity ticker) {
        if ((Object) this instanceof ServerLevel sl
                && RegionTickManager.shouldParallelTickBlockEntity(sl, ticker)) {
            // BE 三档调度：仅 allow 列表且未被台账降级的 BE 进区域 worker
            RegionTickManager.collectBlockEntityTick(sl, ticker);
        } else if (DimensionTickManager.inDimensionTick() && (Object) this instanceof ServerLevel sl) {
            // 维度并行激活期：其余 BE 依赖主线程 Bukkit API，排队到主线程 POST 段执行
            RegionTickManager.queueMainThreadBlockEntityTick(sl, ticker);
        } else {
            ticker.tick();
        }
    }

    @Inject(method = "tickBlockEntities", at = @At("RETURN"))
    private void arclight$regionBlockEntityTickRun(CallbackInfo ci) {
        if ((Object) this instanceof ServerLevel) {
            RegionTickManager.runBlockEntityTickPhase((ServerLevel) (Object) this);
        }
    }
}
