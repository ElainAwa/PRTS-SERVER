package io.izzel.arclight.i18n.conf;

import ninja.leaping.configurate.objectmapping.Setting;
import ninja.leaping.configurate.objectmapping.serialize.ConfigSerializable;

@ConfigSerializable
public class NetworkOptimizationSpec {

    @Setting("enabled")
    private boolean enabled = false;

    // Netty 事件循环线程数。0 = 让核心按 CPU 自动选择（推荐）。
    @Setting("netty-threads")
    private int nettyThreads = 0;

    // 合并发往同一玩家的同类数据包，减少网络线程压力
    @Setting("packet-batching")
    private boolean packetBatching = true;

    public boolean isEnabled() {
        return enabled;
    }

    public int getNettyThreads() {
        return nettyThreads;
    }

    public boolean isPacketBatching() {
        return packetBatching;
    }
}
