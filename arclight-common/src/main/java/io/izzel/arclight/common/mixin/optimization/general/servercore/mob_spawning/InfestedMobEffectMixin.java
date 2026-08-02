package io.izzel.arclight.common.mixin.optimization.general.servercore.mob_spawning;

import io.izzel.arclight.common.optimization.general.servercore.ServerCoreConfig;
import io.izzel.arclight.common.optimization.general.servercore.mob_spawning.Mobcaps;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net/minecraft/world/effect/InfestedMobEffect")
public class InfestedMobEffectMixin {

    @Inject(
            method = "onMobHurt",
            cancellable = true,
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/util/function/ToIntFunction;applyAsInt(Ljava/lang/Object;)I",
                    ordinal = 0
            )
    )
    private void servercore$enforceMobcap(LivingEntity entity, int amplifier, DamageSource source, float damage, CallbackInfo ci) {
        boolean canSpawn = Mobcaps.canSpawnForCategory(
                entity.level(),
                entity.chunkPosition(),
                EntityType.SILVERFISH.getCategory(),
                ServerCoreConfig.mobSpawning().infested()
        );

        if (!canSpawn) {
            ci.cancel();
        }
    }
}
