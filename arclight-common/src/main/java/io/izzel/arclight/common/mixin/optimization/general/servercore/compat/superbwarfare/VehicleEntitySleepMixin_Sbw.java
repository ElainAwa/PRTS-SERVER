package io.izzel.arclight.common.mixin.optimization.general.servercore.compat.superbwarfare;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import io.izzel.arclight.common.compat.superbwarfare.SbwVehicleSleepPolicy;
import io.izzel.arclight.common.mod.mixins.annotation.LoadIfMod;
import io.izzel.arclight.common.optimization.general.servercore.RegionTickManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * SBW §2.16：空车休眠策略注册点。
 *
 * <p>载具首次实例化时把 {@link SbwVehicleSleepPolicy} 注册到 RegionTickManager
 * （幂等，载具 spawn 频率下 volatile 写成本可忽略）。@LoadIfMod 守卫保证无 SBW
 * 时本类永不加载——策略实现类与 SBW 类引用也随本类一起不被触碰（惰性类加载）。</p>
 */
@LoadIfMod(modid = "superbwarfare", condition = LoadIfMod.ModCondition.PRESENT)
@Mixin(value = VehicleEntity.class, remap = false)
public abstract class VehicleEntitySleepMixin_Sbw {

    @Inject(method = "<init>", at = @At("RETURN"))
    private void arclight$registerSleepPolicy(CallbackInfo ci) {
        RegionTickManager.setVehicleSleepPolicy(SbwVehicleSleepPolicy.INSTANCE);
    }
}
