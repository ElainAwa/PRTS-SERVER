package io.izzel.arclight.common.mixin.optimization.general.servercore.mob_spawning;

import io.izzel.arclight.common.optimization.general.servercore.ServerCoreConfig;
import io.izzel.arclight.common.optimization.general.servercore.mob_spawning.Mobcaps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(Zombie.class)
public abstract class ZombieMixin extends Monster {
    private ZombieMixin(EntityType<? extends Monster> entityType, Level level) {
        super(entityType, level);
    }

    // 1.20.1 无 mixinextras：@ModifyExpressionValue 改写为 @Redirect，按 GameRules key 校验避免误伤。
    @Redirect(
            method = "hurt",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/GameRules;getBoolean(Lnet/minecraft/world/level/GameRules$Key;)Z"
            )
    )
    private boolean servercore$enforceMobcap(GameRules gameRules, GameRules.Key<GameRules.BooleanValue> key) {
        boolean doMobSpawning = gameRules.getBoolean(key);
        if (!doMobSpawning || key != GameRules.RULE_DOMOBSPAWNING || !(this.level() instanceof ServerLevel level)) {
            return doMobSpawning;
        }
        return Mobcaps.canSpawnForCategory(
                level,
                this.chunkPosition(),
                EntityType.ZOMBIE.getCategory(),
                ServerCoreConfig.mobSpawning().zombieReinforcements()
        );
    }
}
