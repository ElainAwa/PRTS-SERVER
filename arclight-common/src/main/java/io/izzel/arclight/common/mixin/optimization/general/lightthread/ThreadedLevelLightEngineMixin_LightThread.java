/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 *
 * This file adapts code from C2ME by ishland (RelativityMC)
 * (https://github.com/RelativityMC/C2ME-fabric, c2me-threading-lighting),
 * licensed under MIT.
 * Original code Copyright (c) RelativityMC.
 */

package io.izzel.arclight.common.mixin.optimization.general.lightthread;

import io.izzel.arclight.common.compat.prts.PRTSFeaturesConfig;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import org.spongepowered.asm.mixin.Dynamic;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 光照线程化的配套补跑：light 任务 lambda（{@code runUpdate()} 后清
 * {@code scheduled} 标志）执行完 RETURN 处再调一次 {@code tryScheduleUpdate()}，
 * 让光线程一次任务后连续消费存量工作，减少邮箱反复唤醒的开销。
 *
 * <p>目标方法为 {@code tryScheduleUpdate()} 内部的调度 lambda（SRG {@code m_215156_}，
 * 21.1.248 mojmap jar javap 核实）；注入点必须在该 lambda 而非 {@code runUpdate()}——
 * {@code scheduled} 标志在 {@code runUpdate()} 返回后才清零，提前补跑会被 CAS 拦掉。
 * 与 {@code LightEngineMixin_LightBudget} 正交：预算控每 tick 速率，这里只控消费节奏。
 */
@Mixin(ThreadedLevelLightEngine.class)
public abstract class ThreadedLevelLightEngineMixin_LightThread {

    @Dynamic("lambda inside tryScheduleUpdate: runUpdate() + scheduled.set(false); C2ME method_19505 equivalent")
    @Inject(method = "lambda$tryScheduleUpdate$27", at = @At("RETURN"))
    private void prts$rescheduleAfterLightRun(CallbackInfo ci) {
        if (PRTSFeaturesConfig.lightThreadEnabled) {
            ((ThreadedLevelLightEngine) (Object) this).tryScheduleUpdate();
        }
    }
}
