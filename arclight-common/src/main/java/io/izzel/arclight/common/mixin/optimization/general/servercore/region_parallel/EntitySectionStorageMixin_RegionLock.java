package io.izzel.arclight.common.mixin.optimization.general.servercore.region_parallel;

import io.izzel.arclight.common.optimization.general.servercore.EntityLockManager;
import it.unimi.dsi.fastutil.longs.Long2ObjectFunction;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongSortedSet;
import net.minecraft.world.level.entity.EntitySection;
import net.minecraft.world.level.entity.EntitySectionStorage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * PRTS region parallelism: guard {@code EntitySectionStorage} index writes (P3 v05).
 *
 * <p>{@code getOrCreateSection} is rewritten from a single
 * {@code computeIfAbsent} into a lock-free fast path (section already exists —
 * the hot entity-move case) plus a write-locked create path with a
 * double-check, so two workers cannot concurrently create/resize the shared
 * {@code sections} map. {@code remove} (called by
 * {@code PersistentEntitySectionManager.removeSectionIfEmpty}) locks the
 * {@code LongSortedSet} mutation, which is not safe under concurrent remove.
 * The AABB read traversal keeps lock-free {@code get} calls (fastutil get
 * never mutates structure).</p>
 */
@Mixin(EntitySectionStorage.class)
public abstract class EntitySectionStorageMixin_RegionLock {

    @Shadow @Final private LongSortedSet sectionIds;

    @Invoker("createSection")
    abstract EntitySection<?> arclight$createSection(long sectionPos);

    @Redirect(method = "getOrCreateSection(J)Lnet/minecraft/world/level/entity/EntitySection;",
        at = @At(value = "INVOKE", target = "Lit/unimi/dsi/fastutil/longs/Long2ObjectMap;computeIfAbsent(JLit/unimi/dsi/fastutil/longs/Long2ObjectFunction;)Ljava/lang/Object;"))
    private Object arclight$getOrCreateSection(Long2ObjectMap<EntitySection<?>> map, long key, Long2ObjectFunction<?> func) {
        Object existing = map.get(key);
        if (existing != null) {
            return existing;
        }
        EntityLockManager.INDEX_LOCK.writeLock().lock();
        try {
            existing = map.get(key);
            if (existing != null) {
                return existing;
            }
            EntitySection<?> created = this.arclight$createSection(key);
            map.put(key, created);
            this.sectionIds.add(key);
            return created;
        } finally {
            EntityLockManager.INDEX_LOCK.writeLock().unlock();
        }
    }

    @Redirect(method = "remove(J)V",
        at = @At(value = "INVOKE", target = "Lit/unimi/dsi/fastutil/longs/Long2ObjectMap;remove(J)Ljava/lang/Object;"))
    private Object arclight$sectionStorageRemove(Long2ObjectMap<EntitySection<?>> map, long key) {
        EntityLockManager.INDEX_LOCK.writeLock().lock();
        try {
            return map.remove(key);
        } finally {
            EntityLockManager.INDEX_LOCK.writeLock().unlock();
        }
    }

    @Redirect(method = "remove(J)V",
        at = @At(value = "INVOKE", target = "Lit/unimi/dsi/fastutil/longs/LongSortedSet;remove(J)Z"))
    private boolean arclight$sectionIdsRemove(LongSortedSet set, long key) {
        EntityLockManager.INDEX_LOCK.writeLock().lock();
        try {
            return set.remove(key);
        } finally {
            EntityLockManager.INDEX_LOCK.writeLock().unlock();
        }
    }
}
