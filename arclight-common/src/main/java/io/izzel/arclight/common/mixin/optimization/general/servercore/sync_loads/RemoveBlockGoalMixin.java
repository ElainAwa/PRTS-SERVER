package io.izzel.arclight.common.mixin.optimization.general.servercore.sync_loads;

import io.izzel.arclight.common.optimization.general.servercore.ChunkManager;
import io.izzel.arclight.common.optimization.general.servercore.ServerCoreConfig;
import io.izzel.arclight.common.optimization.general.servercore.ServerCoreConfig.Feature;
import net.minecraft.world.entity.ai.goal.RemoveBlockGoal;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

// Ported from Wesley1808/ServerCore (Mojmap / 1.21.1).
@Mixin(RemoveBlockGoal.class)
public class RemoveBlockGoalMixin {

    @Redirect(method = "isValidTarget", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/LevelReader;getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;"))
    private ChunkAccess servercore$onlyValidateIfLoaded(LevelReader level, int x, int z, ChunkStatus status, boolean create) {
        if (!ServerCoreConfig.isEnabled(Feature.SYNC_LOADS)) return level.getChunk(x, z, status, create);
        return ChunkManager.getChunkNow(level, x, z);
    }
}
