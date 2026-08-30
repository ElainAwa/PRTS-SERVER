/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.mixin.optimization.general.servercore.compat;

import com.simibubi.create.content.kinetics.chainDrive.ChainDriveBlock;
import io.izzel.arclight.common.bridge.core.world.server.ServerChunkCacheRegionBridge;
import io.izzel.arclight.common.mod.mixins.annotation.LoadIfMod;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * [修复] 链条消失/变单条（重放后仍可能再次误判 PART）。
 *
 * 根因：ChainDriveBlock.updateShape 在并行下处理邻居更新时，若邻居区块
 * 未真实 FULL（空壳读取返回 air），链条误判支撑消失 → PART 变 NONE 甚至
 * 被移除——而 KineticNetwork（BE 连接）不受影响 → 装置"空转"（动力还在、
 * 方块视觉缺失）。
 *
 * 修复：updateShape 前检查邻居所在区块是否真实加载；未加载则跳过本次
 * 更新（返回原 state，不误判），等邻居真实就绪后由后续更新恢复。
 */
@LoadIfMod(modid = "create", condition = LoadIfMod.ModCondition.PRESENT)
@Mixin(value = ChainDriveBlock.class, remap = false)
public abstract class ChainDriveBlockMixin_GuardShapeUpdate {

    @Inject(method = "updateShape", at = @At("HEAD"), cancellable = true)
    private void prts$guardShapeAgainstEmptyNeighbor(BlockState state, Direction dir, BlockState neighbor,
                                                     LevelAccessor level, net.minecraft.core.BlockPos pos,
                                                     net.minecraft.core.BlockPos neighborPos,
                                                     CallbackInfoReturnable<BlockState> cir) {
        if (level instanceof ServerLevel serverLevel
                && serverLevel.getChunkSource() instanceof ServerChunkCacheRegionBridge bridge) {
            if (!bridge.arclight$hasLiveChunk(neighborPos.getX() >> 4, neighborPos.getZ() >> 4)) {
                // 邻居区块未真实加载（空壳）：跳过更新，防 PART 误判
                cir.setReturnValue(state);
            }
        }
    }
}
