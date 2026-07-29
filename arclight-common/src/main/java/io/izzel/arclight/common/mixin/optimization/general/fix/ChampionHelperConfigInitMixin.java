package io.izzel.arclight.common.mixin.optimization.general.fix;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Method;

/** Champions 配置惰性初始化：避免 Forge 模组层 private static final 反射被拒导致 NPE。改调公开 bakeCommon()/bake()。 */

@Pseudo
@Mixin(targets = "top.theillusivec4.champions.common.util.ChampionHelper", remap = false)
public class ChampionHelperConfigInitMixin {

    private static final Logger LOGGER = LogManager.getLogger("PRTS-ChampionsFix");
    private static volatile boolean luminara$done = false;

    private static void luminara$ensureConfigLoaded() {
        if (luminara$done) {
            return;
        }
        synchronized (ChampionHelperConfigInitMixin.class) {
            if (luminara$done) {
                return;
            }
            try {
                Class<?> cfg = Class.forName("top.theillusivec4.champions.common.config.ChampionsConfig");
                // bakeCommon() 灌 entitiesList/dimensionList/bossBarBlackList 等（isValidEntity/isValidDimension 依赖）
                invokeIfPresent(cfg, "bakeCommon");
                // bake() 灌 growth/affix/rank/stage 等其余配置
                invokeIfPresent(cfg, "bake");
                LOGGER.info("[PRTS-ChampionsFix] ChampionsConfig 惰性初始化完成（bake）");
            } catch (Throwable t) {
                LOGGER.warn("[PRTS-ChampionsFix] 惰性初始化 ChampionsConfig 失败: {}", t.getMessage());
            }
            luminara$done = true;
        }
    }

    private static void invokeIfPresent(Class<?> cfg, String name) {
        try {
            Method m = cfg.getMethod(name);
            m.invoke(null);
        } catch (NoSuchMethodException ignored) {
            // 该版本没有此方法，跳过
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    @Inject(method = "checkPotential", at = @At("HEAD"), require = 0, remap = false)
    private static void luminara$onCheckPotential(CallbackInfoReturnable ci) {
        luminara$ensureConfigLoaded();
    }

    @Inject(method = "isValidEntity", at = @At("HEAD"), require = 0, remap = false)
    private static void luminara$onIsValidEntity(CallbackInfoReturnable ci) {
        luminara$ensureConfigLoaded();
    }

    @Inject(method = "isValidDimension", at = @At("HEAD"), require = 0, remap = false)
    private static void luminara$onIsValidDimension(CallbackInfoReturnable ci) {
        luminara$ensureConfigLoaded();
    }

    @Inject(method = "isValidChampion", at = @At("HEAD"), require = 0, remap = false)
    private static void luminara$onIsValidChampion(CallbackInfoReturnable ci) {
        luminara$ensureConfigLoaded();
    }
}
