package io.izzel.arclight.common.mixin.optimization.general.servercore.sync_loads;

import io.izzel.arclight.common.optimization.general.servercore.ChunkManager;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.Bee;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(Bee.class)
public abstract class BeeMixin extends Animal {
    @Shadow
    BlockPos hivePos;

    private BeeMixin(EntityType<? extends Animal> entityType, Level level) {
        super(entityType, level);
    }

    // Don't load chunks to validate the hive position.
    @Redirect(
            method = "isHiveValid",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/animal/Bee;isTooFarAway(Lnet/minecraft/core/BlockPos;)Z"
            )
    )
    private boolean servercore$onlyValidateIfLoaded(Bee instance, BlockPos pos) {
        boolean isTooFarAway = ((BeeAccessor) instance).arclight$isTooFarAway(pos);
        return isTooFarAway || !ChunkManager.hasChunk(this.level(), this.hivePos);
    }
}
