/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.optimization.general.servercore.compat;

/**
 * Deployer 装配容差修复的跨 mixin 访问接口（普通接口，非 mixin）：
 * BeltDeployerCallbacksMixin_TimerTolerance 的 @Redirect handler 若强引用
 * DeployerBlockEntityMixin_EnsurePlayer（另一个 mixin 类），mixin 应用
 * BeltDeployerCallbacks 时会要求该 mixin 已应用——类加载顺序不同则
 * "unable to find corresponding type" FATAL（生产实测：Belt 先于 Deployer
 * 加载即炸；zcl 顺序相反则正常）。改经本接口访问，消除交叉依赖。
 */
public interface DeployerTimerAccessor {

    /** timer 字段读取（DeployerBlockEntity.timer，包私有）。 */
    int prts$getTimer();

    /** 每周期至多激活一次的原子守卫；成功占用返回 true。 */
    boolean prts$tryMarkBeltActivation(long now);
}
