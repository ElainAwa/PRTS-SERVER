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
