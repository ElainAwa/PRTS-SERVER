package io.izzel.arclight.common.optimization.general.servercore.compat;

/**
 * KineticNetwork 容量修复桥：源存在但缓存容量非正时强制重算。
 */
public interface KineticNetworkRepairBridge {

    boolean prts$repairCapacityIfNeeded();

    boolean prts$needsSourceHeal();

    boolean prts$resetSourceLessNetwork();
}
