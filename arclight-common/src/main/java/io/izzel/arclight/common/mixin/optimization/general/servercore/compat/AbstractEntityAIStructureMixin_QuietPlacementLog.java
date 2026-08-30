/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.mixin.optimization.general.servercore.compat;

import com.minecolonies.core.entity.ai.workers.AbstractEntityAIStructure;
import io.izzel.arclight.common.mod.mixins.annotation.LoadIfMod;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 建筑工人跨区放置日志降噪：region worker 上跨区 setBlock 被 journal 化（下一 tick 由
 * 目标区域应用，方块最终放置成功），调用方当 tick 拿到 false → minecolonies 记 ERROR
 * 「Failed placement at: ...」并每 tick 重试 → 生产刷屏（实测每秒 1-3 条）。功能不受影响
 * （journal 正常应用），仅把该类 error(String,Object) 降为 debug 保留可查。
 */
@LoadIfMod(modid = "minecolonies", condition = LoadIfMod.ModCondition.PRESENT)
@Mixin(value = AbstractEntityAIStructure.class, remap = false)
public abstract class AbstractEntityAIStructureMixin_QuietPlacementLog {

    @Redirect(method = "*",
            at = @At(value = "INVOKE", target = "Lorg/apache/logging/log4j/Logger;error(Ljava/lang/String;Ljava/lang/Object;)V"))
    private void prts$quietPlacementError(Logger logger, String message, Object arg) {
        logger.debug(message, arg);
    }
}
