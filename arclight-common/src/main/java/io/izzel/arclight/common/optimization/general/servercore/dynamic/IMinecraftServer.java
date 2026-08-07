package io.izzel.arclight.common.optimization.general.servercore.dynamic;

/**
 * MinecraftServer duck interface，持有动态管理器实例（移植自 ServerCore IMinecraftServer，精简为仅 dynamic 所需）。
 */
public interface IMinecraftServer {
    void servercore$setDynamicManager(DynamicManager manager);

    DynamicManager servercore$getDynamicManager();
}
