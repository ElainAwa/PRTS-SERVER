package io.izzel.arclight.common.mixin.optimization.general.servercore.mob_spawning;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import io.izzel.arclight.common.optimization.general.servercore.ServerCoreConfig;
import io.izzel.arclight.common.optimization.general.servercore.mob_spawning.Mobcaps;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(NetherPortalBlock.class)
public class NetherPortalBlockMixin {

    @ModifyExpressionValue(
            method = "randomTick",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/block/state/BlockState;isValidSpawn(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/entity/EntityType;)Z"
            )
    )
    private boolean servercore$enforceMobcap(boolean isValidSpawn, BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        return isValidSpawn && Mobcaps.canSpawnForCategory(
                level,
                new ChunkPos(pos),
                EntityType.ZOMBIFIED_PIGLIN.getCategory(),
                ServerCoreConfig.mobSpawning().portalRandomTicks()
        );
    }
}
