package io.izzel.arclight.common.mixin.optimization.general.fix;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Method;

/**
 * 修复 Champions 模组在 Arclight/Luminara 混合核心下 {@code ChampionsConfig} 的静态配置字段
 * (entitiesList / entitiesPermission / dimensionList / dimensionPermission / bossBarBlackList 等)
 * 未被 Forge 配置事件灌入、保持 null，导致生物生成时
 * ChampionHelper.isValidEntity 调用 entitiesList.contains(...) 抛出 NPE 把服务端搞挂的问题。
 *
 * 旧方案用反射直接读/写 ChampionsConfig 的 private static final 字段，被 Forge 模组层
 * ("class ...ChampionHelper (in module champions) cannot access a member of class ChampionsConfig
 * with modifiers private static final") 拒绝，惰性初始化静默失败、防护失效。
 *
 * 本方案完全避免私有字段反射：Champions 自己的配置加载逻辑就是把 TOML 值灌进这些 public static
 * 字段的公开方法 bakeCommon()/bake()。在 ChampionHelper 所在的（champions 模块）上下文里重新调一次
 * 这两个公开方法即可——它们只读 public 的 ForgeConfigSpec holder 并写 public static 字段，
 * 不涉及任何私有成员访问，故不受模组层包访问限制。
 *
 * 用 @Pseudo：Champions 模组不存在时静默跳过，零影响。
 */
@Pseudo
@Mixin(targets = "top.theillusivec4.champions.common.util.ChampionHelper", remap = false)
public class ChampionHelperConfigInitMixin {

    private static final Logger LOGGER = LogManager.getLogger("Luminara-ChampionsFix");
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
                LOGGER.info("[Luminara-ChampionsFix] ChampionsConfig 惰性初始化完成（bake）");
            } catch (Throwable t) {
                LOGGER.warn("[Luminara-ChampionsFix] 惰性初始化 ChampionsConfig 失败: {}", t.getMessage());
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
