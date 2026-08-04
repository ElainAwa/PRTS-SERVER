package io.izzel.arclight.common.mixin.optimization.general.servercore.mob_spawning;

import io.izzel.arclight.common.optimization.general.servercore.ServerCoreConfig;
import io.izzel.arclight.common.optimization.general.servercore.mob_spawning.Mobcaps;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(NetherPortalBlock.class)
public class NetherPortalBlockMixin {

    // 1.20.1 无 mixinextras：@ModifyExpressionValue 改写为 @Redirect。
    @Redirect(
            method = "randomTick",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/block/state/BlockState;isValidSpawn(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/entity/EntityType;)Z"
            )
    )
    private boolean servercore$enforceMobcap(BlockState state, BlockGetter level, BlockPos pos, EntityType<?> entityType) {
        boolean isValidSpawn = state.isValidSpawn(level, pos, entityType);
        if (!isValidSpawn || !(level instanceof ServerLevel serverLevel)) {
            return isValidSpawn;
        }
        return Mobcaps.canSpawnForCategory(
                serverLevel,
                new ChunkPos(pos),
                EntityType.ZOMBIFIED_PIGLIN.getCategory(),
                ServerCoreConfig.mobSpawning().portalRandomTicks()
        );
    }
}
