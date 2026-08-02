package io.izzel.arclight.common.bridge.optimization;

import io.izzel.arclight.common.optimization.general.servercore.activation_range.ActivationType;

/**
 * 激活范围实体桥（替代 ServerCore 的 ActivationEntity + Inactive 接口注入）。
 */
public interface EntityBridge_ActivationRange {

    ActivationType bridge$getActivationType();

    boolean bridge$isExcludedFromActivation();

    int bridge$getActivatedTick();

    void bridge$setActivatedTick(int activatedTick);

    int bridge$getActivatedImmunityTick();

    void bridge$setActivatedImmunityTick(int activatedImmunityTick);

    boolean bridge$isInactive();

    void bridge$setInactive(boolean inactive);

    void bridge$incFullTickCount();

    int bridge$getFullTickCount();

    void bridge$inactiveTick();
}
