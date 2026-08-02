package io.izzel.arclight.common.bridge.optimization;

/**
 * GoalSelector 的非激活 tick 桥（对应 ServerCore Inactive 接口）。
 */
public interface GoalSelectorBridge_ActivationRange {

    void bridge$inactiveTick();
}
