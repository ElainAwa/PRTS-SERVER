package io.izzel.arclight.i18n.conf;

import ninja.leaping.configurate.objectmapping.Setting;
import ninja.leaping.configurate.objectmapping.serialize.ConfigSerializable;

@ConfigSerializable
public class VelocityForwardingSpec {

    @Setting("enabled")
    private boolean enabled = false;

    @Setting("secret")
    private String secret = "";

    @Setting("online-mode")
    private boolean onlineMode = true;

    public boolean isEnabled() {
        return enabled;
    }

    public String getSecret() {
        return secret;
    }

    public boolean isOnlineMode() {
        return onlineMode;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public void setOnlineMode(boolean onlineMode) {
        this.onlineMode = onlineMode;
    }
}
