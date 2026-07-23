package io.izzel.arclight.i18n.conf;

import ninja.leaping.configurate.objectmapping.Setting;
import ninja.leaping.configurate.objectmapping.serialize.ConfigSerializable;

@ConfigSerializable
public class AsyncLoggingSpec {

    // 是否启用异步日志 Appender（用 log4j2 AsyncAppender 包裹根日志，日志写入移至后台线程；零感知优化）
    @Setting("enabled")
    private boolean enabled = true;

    public boolean isEnabled() {
        return enabled;
    }
}
