/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.mixin.optimization.general.servercore.region_parallel;

import io.izzel.arclight.common.compat.prts.PRTSFeaturesConfig;
import io.izzel.arclight.common.optimization.general.servercore.RegionTickManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.ticks.LevelTicks;
import net.minecraft.world.ticks.ScheduledTick;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BiConsumer;

/**
 * 计划 tick 分发：block tick 排主线程 POST（worker 执行会丢邻居通知语义），
 * fluid 落原路径；region worker 的 schedule 延迟到主线程。
 */
@Mixin(LevelTicks.class)
public abstract class LevelTicksMixin_RegionBlockTick {

    @Redirect(method = "runCollectedTicks(Ljava/util/function/BiConsumer;)V",
        at = @At(value = "INVOKE", target = "Ljava/util/function/BiConsumer;accept(Ljava/lang/Object;Ljava/lang/Object;)V"))
    private static void arclight$regionScheduledTick(BiConsumer<Object, Object> consumer, Object pos, Object type) {
        ServerLevel level = RegionTickManager.collectingLevel();
        if (type instanceof Block && PRTSFeaturesConfig.parallelRegion && level != null) {
            RegionTickManager.queueMainThreadBlockTick(level, (BlockPos) pos, (Block) type);
        } else {
            consumer.accept(pos, type);
        }
    }

    @Inject(method = "schedule(Lnet/minecraft/world/ticks/ScheduledTick;)V",
        at = @At("HEAD"), cancellable = true)
    private void arclight$regionScheduleLock(ScheduledTick<?> tick, CallbackInfo ci) {
        if (RegionTickManager.isRegionWorker()) {
            RegionTickManager.collectScheduleTick((LevelTicks<?>) (Object) this, tick);
            ci.cancel();
        }
    }
}
