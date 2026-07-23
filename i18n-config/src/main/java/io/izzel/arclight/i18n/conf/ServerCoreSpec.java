package io.izzel.arclight.i18n.conf;

import ninja.leaping.configurate.objectmapping.Setting;
import ninja.leaping.configurate.objectmapping.serialize.ConfigSerializable;

@ConfigSerializable
public class ServerCoreSpec {

    // 是否启用 ServerCore 优化（sync_loads / tickets / biome_lookups / pathfinder，共 12 个 mixin）
    @Setting("enabled")
    private boolean enabled = true;

    public boolean isEnabled() {
        return enabled;
    }
}
