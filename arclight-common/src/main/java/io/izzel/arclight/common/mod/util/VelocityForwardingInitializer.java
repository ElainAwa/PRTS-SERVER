package io.izzel.arclight.common.mod.util;

import io.izzel.arclight.i18n.ArclightConfig;
import io.izzel.arclight.i18n.conf.VelocityForwardingSpec;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Initializer for Velocity Modern Forwarding support
 * Ported from Proxy-Compatible-Forge by adde0109
 */
public class VelocityForwardingInitializer {

    private static final Logger LOGGER = LogManager.getLogger("VelocityForwarding");

    public static ModernForwarding modernForwardingInstance;

    /**
     * Initialize Velocity forwarding if enabled
     */
    public static void initialize() {
        try {
            VelocityForwardingSpec config = ArclightConfig.spec().getCompat().getVelocityForwarding();

            if (config.isEnabled() && config.getSecret() != null && !config.getSecret().trim().isEmpty()) {
                modernForwardingInstance = new ModernForwarding(config.getSecret());
                LOGGER.info("Velocity Modern Forwarding initialized (Proxy-Compatible-Forge port)");
            } else {
                LOGGER.debug("Velocity Modern Forwarding is disabled");
            }

        } catch (Exception e) {
            LOGGER.error("Failed to initialize Velocity Modern Forwarding", e);
        }
    }
    

    
    /**
     * Check if Velocity forwarding is enabled and initialized
     */
    public static boolean isEnabled() {
        return modernForwardingInstance != null;
    }
    
    /**
     * Get the modern forwarding instance
     */
    public static ModernForwarding getInstance() {
        return modernForwardingInstance;
    }
}
