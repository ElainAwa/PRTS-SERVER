/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.mixin.optimization.general.servercore.compat;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.minecolonies.api.compatibility.CompatibilityManager;
import io.izzel.arclight.common.mod.mixins.annotation.LoadIfMod;
import net.minecraft.world.level.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * minecolonies Compat Discovery 容错 + 空怪物列表日志压制。
 *
 * <p>生产环境 {@code discover()} 的 {@code discoverAllItems} 在遍历 CreativeTab 时
 * 会因部分 mod 配置未加载抛异常（实测 vintageimprovements：Cannot get config value
 * before config is loaded）——异常中断 discover → {@code discoverMobs} 从不执行 →
 * {@code monsters} 保持空集合 → {@code getAllMonsters()} 每 tick（region worker 高频
 * 调用）打 ERROR「getAllMonsters when empty」刷屏（实测生产 1170+ 条/分钟级）。
 * 修法：捕获 discoverAllItems 异常继续后续 discover 步骤（monsters 正常填充）；
 * 兜底压制空集合时无意义的 ERROR 日志（返回空集合行为不变）。</p>
 */
@LoadIfMod(modid = "minecolonies", condition = LoadIfMod.ModCondition.PRESENT)
@Mixin(value = CompatibilityManager.class, remap = false)
public abstract class CompatibilityManagerMixin_DiscoverResilient {

    @WrapOperation(method = "discover",
            at = @At(value = "INVOKE", target = "Lcom/minecolonies/api/compatibility/CompatibilityManager;discoverAllItems(Lnet/minecraft/world/level/Level;)V"))
    private void prts$discoverAllItemsSafe(CompatibilityManager self, Level level, Operation<Void> original) {
        try {
            original.call(self, level);
        } catch (Throwable t) {
            LogManager.getLogger("PRTS-MineColoniesCompat")
                    .warn("[compat] discoverAllItems failed (mod config timing); continuing to discoverMobs", t);
        }
    }

    @Redirect(method = "getAllMonsters",
            at = @At(value = "INVOKE", target = "Lorg/apache/logging/log4j/Logger;error(Ljava/lang/String;)V"))
    private void prts$quietEmptyMonsters(Logger logger, String message) {
        // 空集合是合法状态（discover 未完成/兼容失败），返回值不变，仅去 ERROR 刷屏
    }
}
