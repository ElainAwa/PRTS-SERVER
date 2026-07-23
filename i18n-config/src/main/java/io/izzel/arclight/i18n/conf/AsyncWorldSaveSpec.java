package io.izzel.arclight.i18n.conf;

import ninja.leaping.configurate.objectmapping.Setting;
import ninja.leaping.configurate.objectmapping.serialize.ConfigSerializable;

@ConfigSerializable
public class AsyncWorldSaveSpec {

    @Setting("enabled")
    private boolean enabled = true;

    @Setting("timeout-seconds")
    private int timeoutSeconds = 30;

    @Setting("save-world-data")
    private boolean saveWorldData = true;

    public boolean isEnabled() {
        return enabled;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public boolean isSaveWorldData() {
        return saveWorldData;
    }
}
