/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.mixin.optimization.general.servercore.region_parallel;

import io.izzel.arclight.common.optimization.general.servercore.EntityLockManager;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.world.level.entity.EntityTickList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * PRTS region parallelism: guard {@code EntityTickList} index writes (P3 v05).
 *
 * <p>{@code active} is written concurrently when region workers kill entities
 * while the main thread spawns new ones; the fastutil map may resize on put.
 * forEach runs on the owning tick thread before the worker phase (separated by
 * the region latch), so it needs no lock.</p>
 */
@Mixin(EntityTickList.class)
public abstract class EntityTickListMixin_RegionLock {

    @Redirect(method = "add(Lnet/minecraft/world/entity/Entity;)V",
        at = @At(value = "INVOKE", target = "Lit/unimi/dsi/fastutil/ints/Int2ObjectMap;put(ILjava/lang/Object;)Ljava/lang/Object;"))
    private Object arclight$tickListAdd(Int2ObjectMap<Object> map, int id, Object entity) {
        EntityLockManager.INDEX_LOCK.writeLock().lock();
        try {
            return map.put(id, entity);
        } finally {
            EntityLockManager.INDEX_LOCK.writeLock().unlock();
        }
    }

    @Redirect(method = "remove(Lnet/minecraft/world/entity/Entity;)V",
        at = @At(value = "INVOKE", target = "Lit/unimi/dsi/fastutil/ints/Int2ObjectMap;remove(I)Ljava/lang/Object;"))
    private Object arclight$tickListRemove(Int2ObjectMap<Object> map, int id) {
        EntityLockManager.INDEX_LOCK.writeLock().lock();
        try {
            return map.remove(id);
        } finally {
            EntityLockManager.INDEX_LOCK.writeLock().unlock();
        }
    }
}
