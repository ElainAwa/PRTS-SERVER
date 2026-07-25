package io.izzel.arclight.common.mixin.optimization.general.minecrafttweaks;

import io.izzel.arclight.common.optimization.general.minecrafttweaks.MinecraftTweaks;
import net.minecraft.world.level.BaseSpawner;
import net.minecraft.world.level.SpawnData;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 刷怪笼 nextSpawnData 空守卫（源自 Mohist 1.20.1 SpawnerBlockEntity patch，去 Mohist 化）。
 * getSpawner 返回前，若 nextSpawnData 为 null 则补一个默认 SpawnData，防后续读取 NPE 崩。
 */
@Mixin(SpawnerBlockEntity.class)
public abstract class MixinSpawnerBlockEntity_NullGuard {

    @Inject(method = "getSpawner", at = @At("RETURN"))
    private void luminara$guardNextSpawnData(CallbackInfoReturnable<BaseSpawner> cir) {
        if (!MinecraftTweaks.spawnerNullGuardEnabled()) return;
        BaseSpawner spawner = cir.getReturnValue();
        if (spawner == null) return;
        MixinBaseSpawner_Accessor acc = (MixinBaseSpawner_Accessor) (Object) spawner;
        if (acc.luminara$getNextSpawnData() == null) {
            acc.luminara$setNextSpawnData(new SpawnData());
        }
    }
}
