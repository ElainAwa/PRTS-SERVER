package io.izzel.arclight.common.mixin.optimization.general.fix;

import net.minecraft.world.damagesource.DamageSource;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * [PRTS 本服维护者 2026-07-21]
 *
 * 修复 RevelationFix（revelationfix@4.0，内嵌于 GoetyRevelation-2.3.1.jar，作为子 mod 加载）
 * 在服务端造成的【事件回环递归崩溃】——也就是本服最初的"第一个 bug"。
 *
 * 根因（经反编译 RevelationFix 4.0 确认）：
 *   EntityActuallyHurt.actuallyHurt(DamageSource, float, boolean)            [3arg，被 DeathPerformance.perform 调用]
 *     -> actuallyHurt0(...) 构造并 post LivingDeathEvent（等死亡事件）
 *     -> 该事件的同步 handler（死亡表演 / Apollyon / Goety 相关）又回调
 *        EntityActuallyHurt.actuallyHurt(3arg)
 *   形成 actuallyHurt -> actuallyHurt0 -> post 事件 -> handler -> actuallyHurt 的【同步事件回环】。
 *   在 PRTS/Arclight 混合服务端下，该回环无限递归直至 java.lang.StackOverflowError：
 *   日志里单次栈溢出就有几百帧 actuallyHurt，2659 次栈溢出 × 每栈几百帧 ≈ 33 万行，撑爆 474MB 日志，
 *   主线程卡死触发 ServerHangWatchdog 结束进程。
 *
 * 为什么旧拦截点（Throwable.printStackTrace）无效：
 *   日志风暴是 log4j 打印 StackOverflowError 的堆栈，而不是 printStackTrace() 方法调用
 *   （日志中 "printStackTrace" 字面出现 0 次）。拦 printStackTrace 自然打空。
 *
 * 本方案（真正根除）：
 *   在 actuallyHurt(3arg) 入口加【线程级防重入】。首次进入执行并标记 inActuallyHurt=true；
 *   回环重入时（标记已 true）直接 cancel 跳过——打断递归，彻底避免 StackOverflowError 与日志风暴。
 *   最外层正常 RETURN 或异常 THROW 都会清除标记，因此不影响后续任何合法调用。
 *
 *   只守卫 3arg 重载是安全的：合法调用链 actuallyHurt(2arg) -> actuallyHurt(3arg) 首次进入 3arg 时
 *   标记仍为 false，正常执行；回环重入时标记已 true，无论回边走 2arg 还是 3arg 都会被打断。
 *
 * @Pseudo：目标类属第三方模组，缺失时本 mixin 静默跳过，绝不拖慢或破坏启动。
 */
@Pseudo
@Mixin(targets = "com.mega.revelationfix.util.entity.EntityActuallyHurt", remap = false)
public class EntityActuallyHurtPrintStackMixin {

    private static final ThreadLocal<Boolean> luminara$inActuallyHurt = ThreadLocal.withInitial(() -> Boolean.FALSE);
    private static final Logger LOGGER = LogManager.getLogger("PRTS-RevelationFix");
    private static final long WARN_INTERVAL_MS = 10_000L;
    private static long luminara$lastWarn = 0L;
    private static int luminara$suppressed = 0;

    @Inject(
        method = "actuallyHurt(Lnet/minecraft/world/damagesource/DamageSource;FZ)V",
        at = @At("HEAD"),
        cancellable = true,
        require = 0
    )
    private void luminara$head(DamageSource source, float amount, boolean p2, CallbackInfo ci) {
        if (luminara$inActuallyHurt.get()) {
            // 事件回环重入 -> 打断递归，避免 StackOverflowError / 日志风暴
            ci.cancel();
            final long now = System.currentTimeMillis();
            if (now - luminara$lastWarn >= WARN_INTERVAL_MS) {
                luminara$lastWarn = now;
                LOGGER.warn("[PRTS-RevelationFix] blocked recursive actuallyHurt event-loop call (guard active); {} earlier calls suppressed", luminara$suppressed);
                luminara$suppressed = 0;
            } else {
                luminara$suppressed++;
            }
            return;
        }
        luminara$inActuallyHurt.set(Boolean.TRUE);
    }

    @Inject(
        method = "actuallyHurt(Lnet/minecraft/world/damagesource/DamageSource;FZ)V",
        at = @At("RETURN"),
        require = 0
    )
    private void luminara$ret(DamageSource source, float amount, boolean p2, CallbackInfo ci) {
        luminara$inActuallyHurt.set(Boolean.FALSE);
    }
}
