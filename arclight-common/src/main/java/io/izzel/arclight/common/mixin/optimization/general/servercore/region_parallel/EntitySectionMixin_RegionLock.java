/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.mixin.optimization.general.servercore.region_parallel;

import net.minecraft.util.ClassInstanceMultiMap;
import net.minecraft.world.level.entity.EntitySection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Per-section element write lock (mixin-injected per-instance field): uncontended
 * within a region, arbitrates only cross-region writes and main-thread spawns.
 * Read traversal snapshots under the read lock so a main-thread remove does not
 * cause a CME on a region worker iterating the section.
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

    @Redirect(method = "getEntities(Lnet/minecraft/world/phys/AABB;Lnet/minecraft/util/AbortableIterationConsumer;)Lnet/minecraft/util/AbortableIterationConsumer$Continuation;",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/util/ClassInstanceMultiMap;iterator()Ljava/util/Iterator;"))
    @SuppressWarnings("rawtypes")
    private Iterator arclight$sectionEntitiesSnapshot(ClassInstanceMultiMap storage) {
        this.arclight$sectionLock.readLock().lock();
        try {
            return new ArrayList(storage).iterator();
        } finally {
            this.arclight$sectionLock.readLock().unlock();
        }
    }

    @Redirect(method = "getEntities(Lnet/minecraft/world/level/entity/EntityTypeTest;Lnet/minecraft/world/phys/AABB;Lnet/minecraft/util/AbortableIterationConsumer;)Lnet/minecraft/util/AbortableIterationConsumer$Continuation;",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/util/ClassInstanceMultiMap;find(Ljava/lang/Class;)Ljava/util/Collection;"))
    @SuppressWarnings({"unchecked", "rawtypes"})
    private Collection arclight$sectionTypedFind(ClassInstanceMultiMap storage, Class type) {
        // find 内部 createList 既遍历全列表又写类型表，须写锁与增删互斥（双读并发 put 也会坏表）
        this.arclight$sectionLock.writeLock().lock();
        try {
            return storage.find(type);
        } finally {
            this.arclight$sectionLock.writeLock().unlock();
        }
    }

    @Redirect(method = "getEntities(Lnet/minecraft/world/level/entity/EntityTypeTest;Lnet/minecraft/world/phys/AABB;Lnet/minecraft/util/AbortableIterationConsumer;)Lnet/minecraft/util/AbortableIterationConsumer$Continuation;",
        at = @At(value = "INVOKE", target = "Ljava/util/Collection;iterator()Ljava/util/Iterator;"))
    @SuppressWarnings("rawtypes")
    private Iterator arclight$sectionTypedEntitiesSnapshot(Collection entities) {
        this.arclight$sectionLock.readLock().lock();
        try {
            return new ArrayList(entities).iterator();
        } finally {
            this.arclight$sectionLock.readLock().unlock();
        }
    }
}
