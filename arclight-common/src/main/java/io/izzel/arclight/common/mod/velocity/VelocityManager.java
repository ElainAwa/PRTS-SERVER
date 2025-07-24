package io.izzel.arclight.common.mod.velocity;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.izzel.arclight.common.mod.util.log.ArclightI18nLogger;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Manager for Velocity Modern Forwarding functionality
 */
public class VelocityManager {

    private static final Logger LOGGER = ArclightI18nLogger.getLogger("Luminara-Velocity");
    private static VelocityManager instance;
    
    private VelocityForwarding velocityForwarding;
    private final List<String> integratedArgumentTypes = new ArrayList<>();

    private VelocityManager() {
        loadIntegratedArgumentTypes();
    }

    public static VelocityManager getInstance() {
        if (instance == null) {
            instance = new VelocityManager();
        }
        return instance;
    }

    public void initialize() {
        if (VelocityConfig.INSTANCE.isVelocityForwardingEnabled()) {
            String secret = VelocityConfig.INSTANCE.getForwardingSecret();
            this.velocityForwarding = new VelocityForwarding(secret);
            LOGGER.info("Velocity Modern Forwarding enabled");
        } else {
            this.velocityForwarding = null;
            LOGGER.info("Velocity Modern Forwarding disabled");
        }
    }

    public VelocityForwarding getVelocityForwarding() {
        return velocityForwarding;
    }

    public boolean isVelocityForwardingEnabled() {
        return velocityForwarding != null;
    }

    public VelocityConfig getVelocityConfig() {
        return VelocityConfig.INSTANCE;
    }

    public List<String> getIntegratedArgumentTypes() {
        return integratedArgumentTypes;
    }

    private void loadIntegratedArgumentTypes() {
        try (Reader reader = new InputStreamReader(Objects.requireNonNull(
                this.getClass().getResourceAsStream("/velocity_integrated_argument_types.json")))) {
            JsonObject result = new Gson().fromJson(reader, JsonObject.class);
            result.get("entries").getAsJsonArray().iterator()
                    .forEachRemaining(element -> integratedArgumentTypes.add(element.getAsString()));
            LOGGER.debug("Loaded {} integrated argument types", integratedArgumentTypes.size());
        } catch (IOException e) {
            LOGGER.warn("Failed to load integrated argument types, using defaults", e);
            // Add some basic types as fallback
            integratedArgumentTypes.add("brigadier:bool");
            integratedArgumentTypes.add("brigadier:float");
            integratedArgumentTypes.add("brigadier:double");
            integratedArgumentTypes.add("brigadier:integer");
            integratedArgumentTypes.add("brigadier:long");
            integratedArgumentTypes.add("brigadier:string");
        }
    }
}
