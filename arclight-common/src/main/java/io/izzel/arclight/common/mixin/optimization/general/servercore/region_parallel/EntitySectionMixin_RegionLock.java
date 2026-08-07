package io.izzel.arclight.common.mixin.optimization.general.servercore.region_parallel;

import net.minecraft.util.ClassInstanceMultiMap;
import net.minecraft.world.level.entity.EntitySection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * PRTS region parallelism: per-section element write lock (P3 v05).
 *
 * <p>An {@code EntitySection} belongs to exactly one chunk, hence one region:
 * within a region the single worker serializes element writes, so the lock is
 * uncontended. It only arbitrates rare cross-region writes (entity moving
 * across the stripe boundary) and main-thread spawns racing region workers.
 * The lock is a mixin-injected per-instance field (no global map to manage).
 * Read traversal (getEntities) stays lock-free: the main-thread spawn window
 * is separated from the worker phase by the region latch, and cross-region
 * traversal/write overlap is a documented low-risk edge (see v05 doc §2.2).</p>
 */
@Mixin(EntitySection.class)
public abstract class EntitySectionMixin_RegionLock {

    @Unique
    private final ReentrantReadWriteLock arclight$sectionLock = new ReentrantReadWriteLock();

    @Redirect(method = "add(Lnet/minecraft/world/level/entity/EntityAccess;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/util/ClassInstanceMultiMap;add(Ljava/lang/Object;)Z"))
    @SuppressWarnings({"unchecked", "rawtypes"})
    private boolean arclight$sectionAdd(ClassInstanceMultiMap storage, Object entity) {
        this.arclight$sectionLock.writeLock().lock();
        try {
            return storage.add(entity);
        } finally {
            this.arclight$sectionLock.writeLock().unlock();
        }
    }

    @Redirect(method = "remove(Lnet/minecraft/world/level/entity/EntityAccess;)Z",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/util/ClassInstanceMultiMap;remove(Ljava/lang/Object;)Z"))
    @SuppressWarnings({"unchecked", "rawtypes"})
    private boolean arclight$sectionRemove(ClassInstanceMultiMap storage, Object entity) {
        this.arclight$sectionLock.writeLock().lock();
        try {
            return storage.remove(entity);
        } finally {
            this.arclight$sectionLock.writeLock().unlock();
        }
    }
}
