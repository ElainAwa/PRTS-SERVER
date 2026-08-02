package io.izzel.arclight.common.mixin.optimization.general.servercore.breeding_cap;

import io.izzel.arclight.common.optimization.general.servercore.ServerCoreConfig;
import io.izzel.arclight.common.optimization.general.servercore.breeding_cap.BreedingCapConfig;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.ThrowableItemProjectile;
import net.minecraft.world.entity.projectile.ThrownEgg;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(ThrownEgg.class)
public abstract class ThrownEggMixin extends ThrowableItemProjectile {
    private ThrownEggMixin(EntityType<? extends ThrowableItemProjectile> entityType, Level level) {
        super(entityType, level);
    }

    // 源用 mixin-extras @ModifyExpressionValue；本树无 mixin-extras，改原生 @Redirect
    @Redirect(
            method = "onHit",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/util/RandomSource;nextInt(I)I",
                    ordinal = 0
            )
    )
    private int servercore$enforceBreedCap(RandomSource random, int bound) {
        int value = random.nextInt(bound);
        BreedingCapConfig config = ServerCoreConfig.breedingCap();
        if (value == 0 && config.enabled() && config.animals().exceedsLimit(EntityType.CHICKEN, this.level(), this.blockPosition())) {
            return 1;
        }

        return value;
    }
}
