package io.izzel.arclight.neoforge.mixin.eventbridge;

import io.izzel.arclight.common.compat.prts.PRTSFeaturesConfig;
import io.izzel.arclight.common.optimization.general.eventbridge.EventShortcircuitStats;
import io.izzel.arclight.neoforge.mod.event.EventBusQuery;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.ServerLevelAccessor;
import net.neoforged.neoforge.event.EventHooks;
import net.neoforged.neoforge.event.entity.living.MobSpawnEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * P2-3 MobSpawnEvent.PositionCheck short-circuit (plan 2026-08-17 §三/§8.5): the natural
 * spawner fires a PositionCheck event for every spawn attempt — including attempts that
 * ultimately fail — so spawn-dense servers pay construction + dispatch on every candidate.
 *
 * <p>No-listener default is <em>not</em> a plain true: the hook's Result.DEFAULT branch runs
 * the vanilla {@code mob.checkSpawnRules && mob.checkSpawnObstruction} checks (bytecode-
 * verified in {@code EventHooks.checkSpawnPosition}). The short-circuit must inline exactly
 * that pair, so the result is bit-for-bit identical — only the event construction and the
 * empty dispatch are skipped. When a listener exists the original hook runs untouched.</p>
 *
 * <p>Covers both call sites: the per-tick {@code isValidPositionForMob} (NATURAL) and the
 * worldgen {@code performWorldGenSpawning} (CHUNK_GENERATION). Spawner variants
 * ({@code checkSpawnPositionSpawner} in BaseSpawner) are left untouched — player-placed
 * spawners are low-frequency. Safe on region/dimension workers (read-only, lock-free
 * query).</p>
 */
@Mixin(NaturalSpawner.class)
public abstract class NaturalSpawnerMixin_MobSpawnShortcircuit {

    @Redirect(method = "isValidPositionForMob",
            at = @At(value = "INVOKE", remap = false,
                    target = "Lnet/neoforged/neoforge/event/EventHooks;checkSpawnPosition(Lnet/minecraft/world/entity/Mob;Lnet/minecraft/world/level/ServerLevelAccessor;Lnet/minecraft/world/entity/MobSpawnType;)Z"))
    private static boolean arclight$checkSpawnPositionNatural(Mob mob, ServerLevelAccessor level, MobSpawnType spawnType) {
        if (PRTSFeaturesConfig.eventShortcircuitMobSpawnEnabled
                && !EventBusQuery.hasListeners(MobSpawnEvent.PositionCheck.class)) {
            EventShortcircuitStats.increment("spawnPositionSkipped");
            // Result.DEFAULT branch of EventHooks.checkSpawnPosition: vanilla rules + obstruction.
            return mob.checkSpawnRules(level, spawnType) && mob.checkSpawnObstruction(level);
        }
        EventShortcircuitStats.increment("spawnPositionForwarded");
        return EventHooks.checkSpawnPosition(mob, level, spawnType);
    }

    @Redirect(method = "spawnMobsForChunkGeneration",
            at = @At(value = "INVOKE", remap = false,
                    target = "Lnet/neoforged/neoforge/event/EventHooks;checkSpawnPosition(Lnet/minecraft/world/entity/Mob;Lnet/minecraft/world/level/ServerLevelAccessor;Lnet/minecraft/world/entity/MobSpawnType;)Z"))
    private static boolean arclight$checkSpawnPositionWorldgen(Mob mob, ServerLevelAccessor level, MobSpawnType spawnType) {
        if (PRTSFeaturesConfig.eventShortcircuitMobSpawnEnabled
                && !EventBusQuery.hasListeners(MobSpawnEvent.PositionCheck.class)) {
            EventShortcircuitStats.increment("spawnPositionSkipped");
            return mob.checkSpawnRules(level, spawnType) && mob.checkSpawnObstruction(level);
        }
        EventShortcircuitStats.increment("spawnPositionForwarded");
        return EventHooks.checkSpawnPosition(mob, level, spawnType);
    }
}
