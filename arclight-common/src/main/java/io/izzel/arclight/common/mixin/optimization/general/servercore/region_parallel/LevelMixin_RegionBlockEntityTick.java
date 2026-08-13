/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.mixin.optimization.general.servercore.region_parallel;

import io.izzel.arclight.common.compat.prts.PRTSFeaturesConfig;
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
 * 方块实体 tick 的区域并行入口。默认关闭并行（BE 间交互复杂，跨区访问有竞态，
 * 见 parallel.region-block-entity-parallel 配置）：关闭时走原版主线程 tick。
 */
@Mixin(Level.class)
public abstract class LevelMixin_RegionBlockEntityTick {

    @Redirect(method = "tickBlockEntities",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/entity/TickingBlockEntity;tick()V"))
    private void arclight$regionBlockEntityTick(TickingBlockEntity ticker) {
        if (RegionTickManager.regionEnabled() && PRTSFeaturesConfig.regionBlockEntityParallel && (Object) this instanceof ServerLevel sl) {
            RegionTickManager.collectBlockEntityTick(sl, ticker);
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
