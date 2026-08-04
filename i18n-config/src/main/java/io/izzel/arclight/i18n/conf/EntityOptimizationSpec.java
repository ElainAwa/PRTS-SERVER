package io.izzel.arclight.i18n.conf;

import ninja.leaping.configurate.objectmapping.Setting;
import ninja.leaping.configurate.objectmapping.serialize.ConfigSerializable;

@ConfigSerializable
public class EntityOptimizationSpec {

    @Setting("disable-entity-collisions")
    private boolean disableEntityCollisions = false;

    @Setting("reduce-entity-updates")
    private boolean reduceEntityUpdates = true;

    @Setting("entity-update-distance")
    private double entityUpdateDistance = 64.0;

    @Setting("max-entities-per-chunk")
    private int maxEntitiesPerChunk = 100;

    @Setting("max-entities-per-type")
    private int maxEntitiesPerType = 150;

    public boolean isDisableEntityCollisions() {
        return disableEntityCollisions;
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

    // Compatibility method for legacy code
    public double getEntityActivationRange() {
        return entityUpdateDistance;
    }
}
