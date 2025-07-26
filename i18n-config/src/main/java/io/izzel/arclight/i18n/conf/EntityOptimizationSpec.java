package io.izzel.arclight.i18n.conf;

import ninja.leaping.configurate.objectmapping.Setting;
import ninja.leaping.configurate.objectmapping.serialize.ConfigSerializable;

@ConfigSerializable
public class EntityOptimizationSpec {

    @Setting("disable-entity-collisions")
    private boolean disableEntityCollisions = false;

    @Setting("optimize-entity-ai")
    private boolean optimizeEntityAI = true;

    @Setting("entity-activation-range")
    private int entityActivationRange = 32;

    @Setting("entity-cleanup-enabled")
    private boolean entityCleanupEnabled = true;

    @Setting("entity-cleanup-threshold")
    private int entityCleanupThreshold = 600;

    @Setting("entity-freeze-timeout")
    private long entityFreezeTimeout = 10000;

    @Setting("optimize-entity-tick")
    private boolean optimizeEntityTick = true;

    @Setting("entity-tick-distance")
    private double entityTickDistance = 64.0;

    @Setting("reduce-entity-updates")
    private boolean reduceEntityUpdates = true;

    @Setting("entity-update-distance")
    private double entityUpdateDistance = 64.0;

    @Setting("max-entities-per-chunk")
    private int maxEntitiesPerChunk = 50;

    @Setting("max-entities-per-type")
    private int maxEntitiesPerType = 100;

    @Setting("clean-valuable-items")
    private boolean cleanValuableItems = false;

    @Setting("item-max-age")
    private long itemMaxAge = 6000;

    @Setting("cleanup-notification-enabled")
    private boolean cleanupNotificationEnabled = true;

    @Setting("cleanup-warning-time")
    private int cleanupWarningTime = 30;

    @Setting("cleanup-start-message")
    private String cleanupStartMessage = "&6[Luminara] &eEntity cleanup starting in &c{time} &eseconds...";

    @Setting("cleanup-complete-message")
    private String cleanupCompleteMessage = "&6[Luminara] &aEntity cleanup completed! Removed &c{total} &aentities (Dead: &c{dead}&a, Items: &c{items}&a, Dense: &c{dense}&a, Excess: &c{excess}&a)";

    @Setting("cleanup-cancelled-message")
    private String cleanupCancelledMessage = "&6[Luminara] &cEntity cleanup cancelled.";

    public boolean isDisableEntityCollisions() {
        return disableEntityCollisions;
    }

    public boolean isOptimizeEntityAI() {
        return optimizeEntityAI;
    }

    public int getEntityActivationRange() {
        return entityActivationRange;
    }

    public boolean isEntityCleanupEnabled() {
        return entityCleanupEnabled;
    }

    public int getEntityCleanupThreshold() {
        return entityCleanupThreshold;
    }

    public long getEntityFreezeTimeout() {
        return entityFreezeTimeout;
    }

    public boolean isOptimizeEntityTick() {
        return optimizeEntityTick;
    }

    public double getEntityTickDistance() {
        return entityTickDistance;
    }

    public boolean isReduceEntityUpdates() {
        return reduceEntityUpdates;
    }

    public double getEntityUpdateDistance() {
        return entityUpdateDistance;
    }

    public int getMaxEntitiesPerChunk() {
        return maxEntitiesPerChunk;
    }

    public int getMaxEntitiesPerType() {
        return maxEntitiesPerType;
    }

    public boolean isCleanValuableItems() {
        return cleanValuableItems;
    }

    public long getItemMaxAge() {
        return itemMaxAge;
    }

    public boolean isCleanupNotificationEnabled() {
        return cleanupNotificationEnabled;
    }

    public int getCleanupWarningTime() {
        return cleanupWarningTime;
    }

    public String getCleanupStartMessage() {
        return cleanupStartMessage;
    }

    public String getCleanupCompleteMessage() {
        return cleanupCompleteMessage;
    }

    public String getCleanupCancelledMessage() {
        return cleanupCancelledMessage;
    }
}
