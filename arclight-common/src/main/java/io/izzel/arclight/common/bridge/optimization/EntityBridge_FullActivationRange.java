package io.izzel.arclight.common.bridge.optimization;

import io.izzel.arclight.common.optimization.general.servercore.activation_range.ActivationType;

/**
 * 富版激活范围实体桥（ServerCore 移植专用）。
 * 与静态版共用 Entity 类，但接口独立命名，避免与 org.spigotmc 改造的旧桥冲突。
 */
public interface EntityBridge_FullActivationRange {

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
