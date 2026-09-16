/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.mixin.optimization.compat.create;

import com.simibubi.create.content.kinetics.saw.TreeCutter;
import io.izzel.arclight.common.compat.prts.PRTSFeaturesConfig;
import io.izzel.arclight.common.mod.mixins.annotation.LoadIfMod;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * TreeCutter.findTree 的 BFS（logs/roots/leaves/validateCut）既没有大小上限，也会
 * 越界同步加载未加载的区块：BFS 的 getBlockState 对相邻区块触发 Level.getChunk，
 * 锯子切进大型相连结构（原木建筑/巨型装置周边）时，一次搜索就能把一圈未加载区块
 * 连人带 BE 拉进内存——生产实测进服瞬间堆 +10GB（64G 内存也被打爆）且主线程
 * validateCut 内 getChunk 上卡死 80-144 秒。
 *
 * 两层防护（parallel.tree-cutter-node-budget/-time-budget-ms 任一 &lt;= 0 即关闭对应层，
 * 均为 0 时保持 Create 原行为）：
 * <ul>
 *   <li>越界保护：BFS 内 getBlockState 遇到未加载区块返回空气——不加载、不扩展；</li>
 *   <li>预算：节点数或单次耗时超限时队列传空，返回已收集的部分树，锯子随后续破坏渐进砍完。</li>
 * </ul>
 */
@LoadIfMod(modid = "create", condition = LoadIfMod.ModCondition.PRESENT)
@Mixin(value = TreeCutter.class, remap = false)
public abstract class TreeCutterMixin_Create {

    private static final Logger LOGGER = LogManager.getLogger("PRTS-TreeCutter");

    /** 当前线程本次 findTree 已消耗的节点数；validateCut 与各段 BFS 共享同一预算。 */
    private static final ThreadLocal<Integer> PRTS_NODE_COUNT = ThreadLocal.withInitial(() -> 0);
    /** 当前线程本次 findTree 的起始 nanoTime。 */
    private static final ThreadLocal<Long> PRTS_START = ThreadLocal.withInitial(() -> 0L);

    private static volatile long prts$budgetExceeded;

    @Inject(method = "findTree", at = @At("HEAD"), remap = false)
    private static void prts$resetBudget(BlockGetter reader, BlockPos pos,
                                         BlockState brokenState,
                                         CallbackInfoReturnable<?> cir) {
        PRTS_NODE_COUNT.set(0);
        PRTS_START.set(System.nanoTime());
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
        int nodeBudget = PRTSFeaturesConfig.treeCutterNodeBudget;
        long timeBudgetMs = PRTSFeaturesConfig.treeCutterTimeBudgetMs;
        if (nodeBudget <= 0 && timeBudgetMs <= 0) {
            return frontier.isEmpty();
        }
        if (nodeBudget > 0 && PRTS_NODE_COUNT.get() >= nodeBudget) {
            return prts$reportExceeded(frontier);
        }
        if (timeBudgetMs > 0
                && (System.nanoTime() - PRTS_START.get()) / 1_000_000L >= timeBudgetMs) {
            return prts$reportExceeded(frontier);
        }
        PRTS_NODE_COUNT.set(PRTS_NODE_COUNT.get() + 1);
        return frontier.isEmpty();
    }

    private static boolean prts$reportExceeded(java.util.List<?> frontier) {
        long exceeded = ++prts$budgetExceeded;
        if (exceeded == 1 || exceeded % 1000 == 0) {
            LOGGER.warn("[tree-cutter] budget exceeded (count={}), cut truncated to partial tree",
                    exceeded);
        }
        return true;
    }

    /**
     * BFS 的所有 getBlockState 都经此重定向：目标区块未加载时返回空气——
     * 不触发同步加载，BFS 视为非树方块自然停止扩展。
     */
    @Redirect(method = {"findTree", "validateCut"},
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/BlockGetter;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"),
            remap = false)
    private static BlockState prts$safeGetState(BlockGetter reader, BlockPos pos) {
        if (reader instanceof Level level && !level.isLoaded(pos)) {
            return Blocks.VOID_AIR.defaultBlockState();
        }
        return reader.getBlockState(pos);
    }
}
