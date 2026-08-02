package io.izzel.arclight.common.mixin.optimization.general.servercore.features.ticking;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import io.izzel.arclight.common.optimization.general.servercore.ChunkManager;
import io.izzel.arclight.common.optimization.general.servercore.ServerCoreConfig;
import io.izzel.arclight.common.optimization.general.servercore.features.FeatureConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 卡在 1x1 空间的村民降频 tick（源自 Purpur 补丁，经 ServerCore 移植）。
 * 打上记分板标签 exclude_lobotomization 可让单个村民永久豁免。
 */
@Mixin(Villager.class)
public abstract class VillagerMixin_Lobotomize extends AbstractVillager {

    @Unique
    private boolean luminara$lobotomized = false;

    @Unique
    private int luminara$notLobotomizedCount = 0;

    private VillagerMixin_Lobotomize(EntityType<? extends AbstractVillager> entityType, Level level) {
        super(entityType, level);
    }

    @WrapWithCondition(
            method = "customServerAiStep",
            require = 0,
            expect = 0,
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/ai/Brain;tick(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/LivingEntity;)V"
            )
    )
    private boolean luminara$shouldTickBrain(Brain<Villager> brain, ServerLevel level, LivingEntity livingEntity) {
        FeatureConfig config = ServerCoreConfig.features();
        return !config.lobotomizeVillagers()
                || !this.luminara$isLobotomized()
                || this.tickCount % config.lobotomizedTickInterval() == 0
                || this.isUnderWater();
    }

    /** 连续 3 次以上判定为未卡住时，检查频率减半（300 tick → 600 tick）。 */
    @Unique
    private boolean luminara$isLobotomized() {
        if (this.tickCount % (this.luminara$notLobotomizedCount > 3 ? 600 : 300) == 0) {
            this.luminara$lobotomized = !this.getTags().contains(FeatureConfig.EXCLUDE_LOBOTOMIZATION)
                    && (this.isPassenger() || !this.luminara$canTravel());

            if (this.luminara$lobotomized) {
                this.luminara$notLobotomizedCount = 0;
            } else {
                this.luminara$notLobotomizedCount++;
            }
        }
        return this.luminara$lobotomized;
    }

    /** Y 偏移 0.0625 以兼容土径/耕地这类矮方块。 */
    @Unique
    private boolean luminara$canTravel() {
        BlockPos center = BlockPos.containing(this.getX(), this.getY() + 0.0625D, this.getZ());
        ChunkAccess chunk = ChunkManager.getChunkNow(this.level(), center);
        if (chunk == null) {
            return false;
        }

        BlockPos.MutableBlockPos mutable = center.mutable();
        boolean canJump = !this.luminara$hasCollisionAt(chunk, mutable.move(Direction.UP, 2));

        for (Direction direction : Direction.Plane.HORIZONTAL) {
            if (this.luminara$canTravelTo(mutable.setWithOffset(center, direction), canJump)) {
                return true;
            }
        }
        return false;
    }

    @Unique
    private boolean luminara$canTravelTo(BlockPos.MutableBlockPos mutable, boolean canJump) {
        ChunkAccess chunk = ChunkManager.getChunkNow(this.level(), mutable);
        if (chunk == null) {
            return false;
        }

        Block bottom = chunk.getBlockState(mutable).getBlock();
        if (bottom instanceof BedBlock) {
            // 放行床方块，保证铁傀儡农场正常运作
            return true;
        }

        if (this.luminara$hasCollisionAt(chunk, mutable.move(Direction.UP))) {
            return false;
        }

        boolean isTallBlock = bottom instanceof FenceBlock || bottom instanceof FenceGateBlock || bottom instanceof WallBlock;
        return !bottom.hasCollision || (canJump && !isTallBlock && !this.luminara$hasCollisionAt(chunk, mutable.move(Direction.UP)));
    }

    @Unique
    private boolean luminara$hasCollisionAt(ChunkAccess chunk, BlockPos pos) {
        return chunk.getBlockState(pos).getBlock().hasCollision;
    }
}
