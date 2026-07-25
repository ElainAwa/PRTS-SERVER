package io.izzel.arclight.i18n.conf;

import ninja.leaping.configurate.objectmapping.Setting;
import ninja.leaping.configurate.objectmapping.serialize.ConfigSerializable;

@ConfigSerializable
public class OptimizationSpec {

    @Setting("cache-plugin-class")
    private boolean cachePluginClass;

    @Setting("goal-selector-update-interval")
    private int goalSelectorInterval;

    @Setting("use-activation-and-tracking-range")
    private boolean useActivationAndTrackingRange;

    @Setting("nearby-player-index-enabled")
    private boolean nearbyPlayerIndexEnabled = false;

    @Setting("nearby-player-index-verify")
    private boolean nearbyPlayerIndexVerify = true;

    @Setting("optimize-powered-rails")
    private boolean optimizePoweredRails = true;

    public boolean isOptimizePoweredRails() {
        return optimizePoweredRails;
    }

    public boolean isNearbyPlayerIndexEnabled() {
        return nearbyPlayerIndexEnabled;
    }

    public boolean isNearbyPlayerIndexVerify() {
        return nearbyPlayerIndexVerify;
    }

    public boolean useActivationAndTrackingRange() {
        return useActivationAndTrackingRange;
    }

    public boolean isCachePluginClass() {
        return cachePluginClass;
    }

    public int getGoalSelectorInterval() {
        return goalSelectorInterval;
    }
}
