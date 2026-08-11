/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.mixin.optimization.general.servercore.region_parallel;

import io.izzel.arclight.common.optimization.general.servercore.EntityLockManager;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.world.level.entity.EntityLookup;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Map;

/**
 * Guards {@code EntityLookup} index writes: {@code byUuid}/{@code byId} are written
 * from both region workers and the main thread, and the fastutil map may resize on
 * put, so each put/remove is wrapped in an {@link EntityLockManager#INDEX_LOCK}
 * write-lock section. Reads stay lock-free and tolerate a transient stale view.
 */
@Mixin(EntityLookup.class)
public abstract class EntityLookupMixin_RegionLock {

    @Redirect(method = "add(Lnet/minecraft/world/level/entity/EntityAccess;)V",
        at = @At(value = "INVOKE", target = "Ljava/util/Map;put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"))
    private Object arclight$lookupAddUuid(Map<Object, Object> map, Object uuid, Object entity) {
        EntityLockManager.INDEX_LOCK.writeLock().lock();
        try {
            return map.put(uuid, entity);
        } finally {
            EntityLockManager.INDEX_LOCK.writeLock().unlock();
        }
    }

    @Redirect(method = "add(Lnet/minecraft/world/level/entity/EntityAccess;)V",
        at = @At(value = "INVOKE", target = "Lit/unimi/dsi/fastutil/ints/Int2ObjectMap;put(ILjava/lang/Object;)Ljava/lang/Object;"))
    private Object arclight$lookupAddId(Int2ObjectMap<Object> map, int id, Object entity) {
        EntityLockManager.INDEX_LOCK.writeLock().lock();
        try {
            return map.put(id, entity);
        } finally {
            EntityLockManager.INDEX_LOCK.writeLock().unlock();
        }
    }

    @Redirect(method = "remove(Lnet/minecraft/world/level/entity/EntityAccess;)V",
        at = @At(value = "INVOKE", target = "Ljava/util/Map;remove(Ljava/lang/Object;)Ljava/lang/Object;"))
    private Object arclight$lookupRemoveUuid(Map<Object, Object> map, Object uuid) {
        EntityLockManager.INDEX_LOCK.writeLock().lock();
        try {
            return map.remove(uuid);
        } finally {
            EntityLockManager.INDEX_LOCK.writeLock().unlock();
        }
    }

    @Redirect(method = "remove(Lnet/minecraft/world/level/entity/EntityAccess;)V",
        at = @At(value = "INVOKE", target = "Lit/unimi/dsi/fastutil/ints/Int2ObjectMap;remove(I)Ljava/lang/Object;"))
    private Object arclight$lookupRemoveId(Int2ObjectMap<Object> map, int id) {
        EntityLockManager.INDEX_LOCK.writeLock().lock();
        try {
            return map.remove(id);
        } finally {
            EntityLockManager.INDEX_LOCK.writeLock().unlock();
        }
    }
}
