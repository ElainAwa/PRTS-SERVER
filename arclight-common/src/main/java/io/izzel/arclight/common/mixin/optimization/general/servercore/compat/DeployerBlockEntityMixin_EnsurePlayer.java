/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.mixin.optimization.general.servercore.compat;

import com.simibubi.create.content.kinetics.deployer.DeployerBlockEntity;
import com.simibubi.create.content.kinetics.deployer.DeployerFakePlayer;
import io.izzel.arclight.common.mod.mixins.annotation.LoadIfMod;
import io.izzel.arclight.common.optimization.general.servercore.compat.DeployerTimerAccessor;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * [修复] 存档加载的 Deployer 假玩家可能未创建（initialize 在异步反序列化路径未被触发）
 * → player==null → whenItemHeld 永远 HOLD（heldItem 空）→ 装配从不 activate（物品卡传送带，
 * 生产实测 activate=0）。tick 兜底补 initialize（幂等：invHandler 已建则跳过），保证假玩家存在。
 */
@LoadIfMod(modid = "create", condition = LoadIfMod.ModCondition.PRESENT)
@Mixin(value = DeployerBlockEntity.class, remap = false)
public abstract class DeployerBlockEntityMixin_EnsurePlayer implements DeployerTimerAccessor {

    @Shadow(remap = false)
    private DeployerFakePlayer player;

    /** 并行区 worker 之间无 tick 内排序保证：belt 回调与 deployer tick 可任意交错，
     *  原 whenItemHeld 的 state==RETRACTING && timer==1000 精确判定窗口只有 1 tick，
     *  交错/漂移一次即永久错过 → 物品堆积。容差窗口由 BeltDeployerCallbacksMixin_TimerTolerance
     *  实现，本字段为"每周期至多激活一次"防重复加工守卫。 */
    @Unique
    private volatile long prts$lastBeltActivationTick;

    @Unique
    public long prts$getLastBeltActivationTick() {
        return this.prts$lastBeltActivationTick;
    }

    @Unique
    public void prts$setLastBeltActivationTick(long tick) {
        this.prts$lastBeltActivationTick = tick;
    }

    @Accessor("timer")
    public abstract int prts$getTimer();

    @Inject(method = "tick", at = @At("HEAD"))
    private void prts$ensureFakePlayer(CallbackInfo ci) {
        DeployerBlockEntity self = (DeployerBlockEntity) (Object) this;
        if (this.player == null && self.getLevel() instanceof ServerLevel) {
            self.initialize();
        }
    }
}
