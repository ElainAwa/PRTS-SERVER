package io.izzel.arclight.common.mixin.optimization.general.servercore.dynamic;

import io.izzel.arclight.common.optimization.general.servercore.dynamic.DynamicSetting;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 将实际视距/模拟距同步到 DynamicSetting（移植自 ServerCore PlayerListMixin）。
 * 注意：set(value, null) 传 null manager 不会触发 onChanged，避免 setViewDistance 重入死循环。
 */
@Mixin(PlayerList.class)
public class PlayerListMixin {

    @Inject(method = "setViewDistance", at = @At("HEAD"))
    private void prts$updateViewDistance(int value, CallbackInfo ci) {
        DynamicSetting.VIEW_DISTANCE.set(value, null);
    }

    @Inject(method = "setSimulationDistance", at = @At("HEAD"))
    private void prts$updateSimulationDistance(int value, CallbackInfo ci) {
        DynamicSetting.SIMULATION_DISTANCE.set(value, null);
    }
}
