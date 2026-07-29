package io.izzel.arclight.common.mixin.optimization.general.entitytracking;

import io.izzel.arclight.common.optimization.general.entitytracking.EntityTrackerEntryExtension;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.List;

/** 原 VMP com.ishland.vmp.mixins.playerwatching.optimize_nearby_entity_tracking_lookups.MixinEntityTrackerEntry */
@Mixin(ServerEntity.class)
public abstract class ServerEntity_TrackingMixin implements EntityTrackerEntryExtension {

    // @formatter:off
    @Shadow private int tickCount;
    @Shadow @Final private int updateInterval;
    @Shadow @Final private Entity entity;
    @Shadow private List<Entity> lastPassengers;
    @Shadow protected abstract void sendDirtyEntityData();
    @Shadow public abstract void sendChanges();
    // @formatter:on

    @Override
    public void vmp$tickAlways() {
        // 等价于 MathHelper.roundUpToMultiple(tickCount, updateInterval)：把计数对齐到下一个 updateInterval 倍数，
        int rem = this.tickCount % this.updateInterval;
        if (rem != 0) {
            this.tickCount += this.updateInterval - rem;
        }
        this.entity.hurtMarked = true;
        this.sendChanges();
    }

    @Override
    public void vmp$syncEntityData() {
        this.tickCount++;
        if (this.tickCount % this.updateInterval == 0) {
            this.sendDirtyEntityData();
        }
    }

    @Override
    public void vmp$updatePassengers() {
        List<Entity> current = this.entity.getPassengers();
        if (!this.lastPassengers.equals(current)) {
            this.lastPassengers = current;
        }
    }
}
