package io.izzel.arclight.common.mixin.optimization.general.fix;

import net.minecraft.world.damagesource.DamageSource;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 防守卫 RevelationFix actuallyHurt 同步事件回环递归（StackOverflowError / 日志风暴）。线程级 HEAD/RETURN 防重入。 */
@Pseudo
@Mixin(targets = "com.mega.revelationfix.util.entity.EntityActuallyHurt", remap = false)
public class EntityActuallyHurtPrintStackMixin {

    // 记录进入时间戳；RETURN 复位。异常路径悬挂时超时自愈，避免线程永久放行阻塞
    private static final ThreadLocal<Long> luminara$enteredAt = new ThreadLocal<>();
    private static final long STALE_MS = 5000L;
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
        long now = System.currentTimeMillis();
        Long entered = luminara$enteredAt.get();
        if (entered != null) {
            if (now - entered < STALE_MS) {
                // 事件回环重入 -> 打断递归，避免 StackOverflowError / 日志风暴
                ci.cancel();
                if (now - luminara$lastWarn >= WARN_INTERVAL_MS) {
                    luminara$lastWarn = now;
                    LOGGER.warn("[PRTS-RevelationFix] blocked recursive actuallyHurt event-loop call (guard active); {} earlier calls suppressed", luminara$suppressed);
                    luminara$suppressed = 0;
                } else {
                    luminara$suppressed++;
                }
                return;
            }
            // 悬挂超时（异常路径未复位）→ 强制复位放行
            luminara$enteredAt.remove();
        }
        luminara$enteredAt.set(now);
    }

    @Inject(
        method = "actuallyHurt(Lnet/minecraft/world/damagesource/DamageSource;FZ)V",
        at = @At("RETURN"),
        require = 0
    )
    private void luminara$ret(DamageSource source, float amount, boolean p2, CallbackInfo ci) {
        luminara$enteredAt.remove();
    }
}
