package io.izzel.arclight.common.mod.util;

import io.izzel.arclight.i18n.ArclightConfig;
import io.izzel.arclight.i18n.conf.VelocityForwardingSpec;
import net.minecraft.resources.ResourceLocation;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Velocity Modern Forwarding support for Luminara
 * Ported from Proxy-Compatible-Forge by adde0109
 * https://github.com/adde0109/Proxy-Compatible-Forge
 */
public class VelocityProxy {
    
    private static final Logger LOGGER = LogManager.getLogger("VelocityProxy");
    
    public static final ResourceLocation PLAYER_INFO_CHANNEL = new ResourceLocation("velocity", "player_info");
    
    /**
     * Check if Velocity forwarding is enabled
     */
    public static boolean isEnabled() {
        try {
            VelocityForwardingSpec spec = ArclightConfig.spec().getCompat().getVelocityForwarding();
            return spec != null && spec.isEnabled() && spec.getSecret() != null && !spec.getSecret().trim().isEmpty();
        } catch (Exception e) {
            LOGGER.debug("Error checking Velocity forwarding config", e);
            return false;
        }
    }
}
