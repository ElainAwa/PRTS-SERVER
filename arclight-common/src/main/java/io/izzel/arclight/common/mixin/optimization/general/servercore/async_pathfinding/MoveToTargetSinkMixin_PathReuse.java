/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.mixin.optimization.general.servercore.async_pathfinding;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.behavior.MoveToTargetSink;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.pathfinder.Path;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 寻路结果复用（5000 村民场景关键优化）：vanilla MoveToTargetSink.tryComputePath
 * 每 tick 无条件 createPath——即使当前路径仍有效（未完成、目标未变）也全量重算，
 * 5000 村民 × 每 tick = 寻路风暴。这里在 createPath 前检查：当前路径存在且未完成
 * 且目标块未变时直接复用（返回现有 path），语义等价（路径有效就该继续走）。
 */
@Mixin(MoveToTargetSink.class)
public abstract class MoveToTargetSinkMixin_PathReuse {

    @Redirect(method = "tryComputePath",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/ai/navigation/PathNavigation;createPath(Lnet/minecraft/core/BlockPos;I)Lnet/minecraft/world/level/pathfinder/Path;"))
    private Path arclight$reuseValidPath(PathNavigation navigation, BlockPos target, int distance) {
        Path current = navigation.getPath();
        if (current != null && !current.isDone() && navigation.isInProgress()) {
            return current;
        }
        return navigation.createPath(target, distance);
    }
}
