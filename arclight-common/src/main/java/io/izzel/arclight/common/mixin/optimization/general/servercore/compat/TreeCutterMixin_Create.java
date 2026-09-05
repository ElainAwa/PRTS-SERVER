/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.mixin.optimization.general.servercore.compat;

import com.simibubi.create.content.kinetics.saw.TreeCutter;
import io.izzel.arclight.common.compat.prts.PRTSFeaturesConfig;
import io.izzel.arclight.common.mod.mixins.annotation.LoadIfMod;
import net.minecraft.world.level.BlockGetter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * TreeCutter.findTree 的 BFS（logs/roots/leaves/validateCut）没有大小上限，且
 * logs 匹配 BlockTags.LOGS、蘑菇块/下界疣块按树叶处理——锯子切进大型相连结构
 * （原木建筑、蘑菇屋顶等）时主线程被洪水填充卡死数十秒。给 BFS 循环加节点预算：
 * 超预算时队列传空，findTree 返回已收集的部分树，锯子随后续破坏渐进砍完。
 * parallel.tree-cutter-node-budget &lt;= 0 时保持 Create 原行为。
 */
@LoadIfMod(modid = "create", condition = LoadIfMod.ModCondition.PRESENT)
@Mixin(value = TreeCutter.class, remap = false)
public abstract class TreeCutterMixin_Create {

    private static final Logger LOGGER = LogManager.getLogger("PRTS-TreeCutter");

    /** 当前线程本次 findTree 已消耗的节点数；validateCut 与各段 BFS 共享同一预算。 */
    private static final ThreadLocal<Integer> PRTS_NODE_COUNT = ThreadLocal.withInitial(() -> 0);

    private static volatile long prts$budgetExceeded;

    @Inject(method = "findTree", at = @At("HEAD"), remap = false)
    private static void prts$resetBudget(BlockGetter reader, net.minecraft.core.BlockPos pos,
                                         net.minecraft.world.level.block.state.BlockState brokenState,
                                         CallbackInfoReturnable<?> cir) {
        PRTS_NODE_COUNT.set(0);
    }

    @Redirect(method = "findTree",
            at = @At(value = "INVOKE", target = "Ljava/util/List;isEmpty()Z"),
            remap = false)
    private static boolean prts$budgetFindTree(java.util.List<?> frontier) {
        return prts$checkBudget(frontier);
    }

    @Redirect(method = "validateCut",
            at = @At(value = "INVOKE", target = "Ljava/util/List;isEmpty()Z"),
            remap = false)
    private static boolean prts$budgetValidateCut(java.util.List<?> frontier) {
        return prts$checkBudget(frontier);
    }

    private static boolean prts$checkBudget(java.util.List<?> frontier) {
        int budget = PRTSFeaturesConfig.treeCutterNodeBudget;
        if (budget <= 0) {
            return frontier.isEmpty();
        }
        int used = PRTS_NODE_COUNT.get();
        if (used >= budget) {
            long exceeded = ++prts$budgetExceeded;
            if (exceeded == 1 || exceeded % 1000 == 0) {
                LOGGER.warn("[tree-cutter] BFS budget {} exceeded (count={}), cut truncated to partial tree",
                        budget, exceeded);
            }
            return true;
        }
        PRTS_NODE_COUNT.set(used + 1);
        return frontier.isEmpty();
    }
}
