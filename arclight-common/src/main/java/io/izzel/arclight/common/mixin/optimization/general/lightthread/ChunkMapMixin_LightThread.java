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

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.izzel.arclight.common.compat.prts.PRTSFeaturesConfig;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.thread.ProcessorMailbox;
import org.apache.logging.log4j.LogManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 光照线程化：把 light 任务邮箱与任务排序器的执行器从共享后台池
 * （{@code Util.backgroundExecutor()}，worldgen 任务同池）迁到每维度独立单线程，
 * 隔离光照传播与世界生成的线程池争抢（默认关，配置 {@code lighting.threaded}）。
 *
 * <p>vanilla 中 worldgen 风暴会占满共享后台池，光照更新任务排队延迟，
 * {@code waitForPendingTasks}（chunk 装载/保存等待光照）随之阻塞主线程。
 * 独立线程后光照传播不受 worldgen CPU 任务挤压，排序器的消息分发同样不再饥饿。
 *
 * <p>生命周期：线程池随 ChunkMap 实例创建（每维度一个），{@link #close()} 时 shutdown；
 * 与 C2ME 单实例共享池不同，避免多维度关闭时序互相影响。
 *
 * <p>注意：排序器自身邮箱留在原版共享后台池不迁移（2026-08-26 钉死：此前“突发风暴冻结”
 * 实为风暴脚本 forceload 441>256 上限被整拒的幻影现场，非单线程互等；按最保守口径
 * 回归原版布局，仅 light 任务邮箱走独立线程）。
 */
@Mixin(ChunkMap.class)
public abstract class ChunkMapMixin_LightThread {

    @Shadow
    @Final
    ServerLevel level;

    @Unique
    private ExecutorService prts$lightExecutor;

    @WrapOperation(method = "<init>", at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/util/thread/ProcessorMailbox;create(Ljava/util/concurrent/Executor;Ljava/lang/String;)Lnet/minecraft/util/thread/ProcessorMailbox;"))
    private ProcessorMailbox<Runnable> prts$redirectLightMailboxExecutor(Executor executor, String name, Operation<ProcessorMailbox<Runnable>> original) {
        // 构造器内 create 被调用两次（worldgen/light），只替换 light 邮箱的执行器。
        if (!"light".equals(name) || !PRTSFeaturesConfig.lightThreadEnabled) {
            return original.call(executor, name);
        }
        ExecutorService pool = new ThreadPoolExecutor(
                1, 1,
                0L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(),
                new ThreadFactoryBuilder()
                        .setDaemon(true)
                        .setPriority(Thread.NORM_PRIORITY - 1)
                        .setNameFormat(this.level.dimension().location().toString().replace(':', '_') + " - Light")
                        .build());
        this.prts$lightExecutor = pool;
        LogManager.getLogger("PRTS-LightThread").info("[light-thread] dedicated light executor created for {} (thread=\"{} - Light\")",
                this.level.dimension().location(), this.level.dimension().location().toString().replace(':', '_'));
        return original.call(pool, name);
    }

    @Inject(method = "close", at = @At("TAIL"))
    private void prts$shutdownLightExecutor(CallbackInfo ci) {
        if (this.prts$lightExecutor != null) {
            // close() 开头已先关 queueSorter（邮箱停止派单），此处 shutdown 只跑完存量任务。
            this.prts$lightExecutor.shutdown();
            this.prts$lightExecutor = null;
        }
    }
}
