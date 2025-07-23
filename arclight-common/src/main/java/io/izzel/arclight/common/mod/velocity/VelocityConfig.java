package io.izzel.arclight.common.mod.velocity;

import io.izzel.arclight.i18n.ArclightConfig;

/**
 * Configuration for Velocity Modern Forwarding
 */
public class VelocityConfig {

    public static final VelocityConfig INSTANCE = new VelocityConfig();

    private VelocityConfig() {
        // Private constructor for singleton
    }

    public boolean isVelocityForwardingEnabled() {
        return ArclightConfig.spec().getVelocity().isEnabled() &&
               !getForwardingSecret().isEmpty();
    }

    public String getForwardingSecret() {
        return ArclightConfig.spec().getVelocity().getForwardingSecret();
    }

    public boolean isDebugLoggingEnabled() {
        return ArclightConfig.spec().getVelocity().isDebugLogging();
    }
}
