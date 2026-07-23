package io.izzel.arclight.i18n.conf;

import ninja.leaping.configurate.objectmapping.Setting;
import ninja.leaping.configurate.objectmapping.serialize.ConfigSerializable;

@ConfigSerializable
public class RouteBSpec {

    // 是否启用空间化实体追踪（HariPlayer 的 use_optimized_entity_tracking / AreaMap）
    @Setting("enabled")
    private boolean enabled = true;

    // 看门狗硬阈值（毫秒）：单次 AreaMap tick 超过该值，本会话永久回退原版实体循环，防主线程冻结导致客户端超时
    @Setting("watchdog-hard-ms")
    private long watchdogHardMs = 500L;

    // 看门狗软阈值（毫秒）：超过仅记录告警不禁用，用于诊断偶发慢 tick
    @Setting("watchdog-soft-ms")
    private long watchdogSoftMs = 100L;

    public boolean isEnabled() {
        return enabled;
    }

    public long getWatchdogHardMs() {
        return watchdogHardMs;
    }

    public long getWatchdogSoftMs() {
        return watchdogSoftMs;
    }
}
