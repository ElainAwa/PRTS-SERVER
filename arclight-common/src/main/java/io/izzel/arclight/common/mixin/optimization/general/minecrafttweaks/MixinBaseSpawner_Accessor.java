package io.izzel.arclight.common.mixin.optimization.general.minecrafttweaks;

import net.minecraft.world.level.BaseSpawner;
import net.minecraft.world.level.SpawnData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 访问 BaseSpawner.nextSpawnData 字段，供刷怪笼 NPE 守卫使用。
 */
@Mixin(BaseSpawner.class)
public interface MixinBaseSpawner_Accessor {

    @Accessor("nextSpawnData")
    SpawnData luminara$getNextSpawnData();

    @Accessor("nextSpawnData")
    void luminara$setNextSpawnData(SpawnData data);
}
