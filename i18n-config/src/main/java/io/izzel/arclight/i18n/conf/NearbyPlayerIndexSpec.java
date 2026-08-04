package io.izzel.arclight.i18n.conf;

import ninja.leaping.configurate.objectmapping.Setting;
import ninja.leaping.configurate.objectmapping.serialize.ConfigSerializable;

@ConfigSerializable
public class NearbyPlayerIndexSpec {

    // 是否启用空间最近玩家索引（加速 checkDespawn/刷怪笼的全玩家线性扫描）；默认关闭，观察期后再开
    @Setting("enabled")
    private boolean enabled = false;

    // 双跑校验模式：每次索引查询同时跑原版对照，不一致时告警并采用原版结果（已稳定，默认关闭以省双跑开销）
    @Setting("verify")
    private boolean verify = false;

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isVerify() {
        return verify;
    }
}
