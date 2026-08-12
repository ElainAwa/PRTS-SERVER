/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.mixin.optimization.general.servercore.region_parallel;

import io.izzel.arclight.common.optimization.general.servercore.EntityLockManager;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Guards {@code ChunkMap.entityMap} index writes: the fastutil map is written from
 * region workers (entity death drops spawn new entities via
 * {@code ServerLevel.addEntity -> ChunkMap.addEntity}) and the main thread, and may
 * resize on put, so each put/remove is wrapped in an {@link EntityLockManager#INDEX_LOCK}
 * write-lock section. Reads stay lock-free and tolerate a transient stale view,
 * mirroring {@link EntityLookupMixin_RegionLock}.
 */
@Mixin(ChunkMap.class)
public abstract class ChunkMapMixin_EntityIndexLock {

    @Redirect(method = "addEntity(Lnet/minecraft/world/entity/Entity;)V",
        at = @At(value = "INVOKE", target = "Lit/unimi/dsi/fastutil/ints/Int2ObjectMap;put(ILjava/lang/Object;)Ljava/lang/Object;"))
    private Object arclight$chunkMapEntityPut(Int2ObjectMap<Object> map, int id, Object entity) {
        EntityLockManager.INDEX_LOCK.writeLock().lock();
        try {
            return map.put(id, entity);
        } finally {
            EntityLockManager.INDEX_LOCK.writeLock().unlock();
        }
    }

    @Redirect(method = "removeEntity(Lnet/minecraft/world/entity/Entity;)V",
        at = @At(value = "INVOKE", target = "Lit/unimi/dsi/fastutil/ints/Int2ObjectMap;remove(I)Ljava/lang/Object;"))
    private Object arclight$chunkMapEntityRemove(Int2ObjectMap<Object> map, int id) {
        EntityLockManager.INDEX_LOCK.writeLock().lock();
        try {
            return map.remove(id);
        } finally {
            EntityLockManager.INDEX_LOCK.writeLock().unlock();
        }
    }
}
