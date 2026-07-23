package io.izzel.arclight.i18n.conf;

import ninja.leaping.configurate.objectmapping.Setting;
import ninja.leaping.configurate.objectmapping.serialize.ConfigSerializable;

@ConfigSerializable
public class MoveZeroVelocitySpec {

    // 是否启用移动零速度跳过（静止实体且包围盒未变时跳过 Entity.move 的碰撞/位置计算；零感知优化）
    @Setting("enabled")
    private boolean enabled = true;

    public boolean isEnabled() {
        return enabled;
    }
}
