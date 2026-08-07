package io.izzel.arclight.common.optimization.general.servercore.dynamic;

import java.util.List;
import java.util.Map;

/**
 * dynamic 段配置数据类（移植自 ServerCore DynamicConfig，去掉 dazzleconf 改为普通 POJO）。
 */
public class DynamicConfig {
    public static final DynamicConfig DISABLED = new DynamicConfig(false, 35, Map.of(), List.of());

    private final boolean enabled;
    private final int targetMspt;
    private final Map<DynamicSetting, Integer> defaultValues;
    private final List<Setting> settings;

    public DynamicConfig(boolean enabled, int targetMspt, Map<DynamicSetting, Integer> defaultValues, List<Setting> settings) {
        this.enabled = enabled;
        this.targetMspt = targetMspt;
        this.defaultValues = defaultValues;
        this.settings = settings;
    }

    public boolean enabled() {
        return enabled;
    }

    public int targetMspt() {
        return targetMspt;
    }

    public Map<DynamicSetting, Integer> defaultValues() {
        return defaultValues;
    }

    public List<Setting> settings() {
        return settings;
    }
}
