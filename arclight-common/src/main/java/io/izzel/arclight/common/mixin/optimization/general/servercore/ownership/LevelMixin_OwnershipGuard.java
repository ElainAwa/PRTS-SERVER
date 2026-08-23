/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.mixin.optimization.general.servercore.ownership;

import io.izzel.arclight.common.optimization.general.servercore.ownership.CrossRefProbe;
import io.izzel.arclight.common.optimization.general.servercore.ownership.WorldAccessGuard;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.function.Predicate;

/**
 * LevelAccessor boundary guard. Each injected method returns immediately when
 * the policy is OFF or the caller is not a parallel tick worker, so the only
 * cost on the vanilla path is one volatile read plus a ThreadLocal lookup.
 */
@Mixin(Level.class)
public abstract class LevelMixin_OwnershipGuard {

    @Inject(method = "getBlockEntity(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/entity/BlockEntity;",
            at = @At("HEAD"))
    private void arclight$guardGetBlockEntity(BlockPos pos, CallbackInfoReturnable<BlockEntity> cir) {
        Level level = (Level) (Object) this;
        WorldAccessGuard.checkMainOnlyRead(level, pos);
        CrossRefProbe.recordGetBlockEntity(level, pos);
    }

    @Inject(method = "getEntities(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;)Ljava/util/List;",
            at = @At("HEAD"))
    private void arclight$guardGetEntities(Entity ignored, AABB aabb, Predicate<? super Entity> predicate,
                                           CallbackInfoReturnable<List<Entity>> cir) {
        WorldAccessGuard.checkCrossAreaRead((Level) (Object) this, aabb);
    }

    @SuppressWarnings("rawtypes")
    @Inject(method = "getEntities(Lnet/minecraft/world/level/entity/EntityTypeTest;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;)Ljava/util/List;",
            at = @At("HEAD"))
    private void arclight$guardGetEntitiesTyped(net.minecraft.world.level.entity.EntityTypeTest<?, ?> ignored,
                                                AABB aabb, Predicate predicate, CallbackInfoReturnable<List> cir) {
        WorldAccessGuard.checkCrossAreaRead((Level) (Object) this, aabb);
    }

    @Inject(method = "removeBlock(Lnet/minecraft/core/BlockPos;Z)Z", at = @At("HEAD"))
    private void arclight$guardRemoveBlock(BlockPos pos, boolean isMoving, CallbackInfoReturnable<Boolean> cir) {
        WorldAccessGuard.checkMainOnlyWrite((Level) (Object) this, pos);
    }

    @Inject(method = "destroyBlock(Lnet/minecraft/core/BlockPos;ZLnet/minecraft/world/entity/Entity;I)Z", at = @At("HEAD"))
    private void arclight$guardDestroyBlock(BlockPos pos, boolean dropResources, Entity entity, int recursionLeft,
                                            CallbackInfoReturnable<Boolean> cir) {
        WorldAccessGuard.checkMainOnlyWrite((Level) (Object) this, pos);
    }

    @Inject(method = "blockEvent(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Block;II)V", at = @At("HEAD"))
    private void arclight$guardBlockEvent(BlockPos pos, Block block, int eventId, int eventParam,
                                          org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        WorldAccessGuard.checkMainOnlyWrite((Level) (Object) this, pos);
    }

    @Inject(method = "updateNeighborsAt(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Block;)V", at = @At("HEAD"))
    private void arclight$guardUpdateNeighborsAt(BlockPos pos, Block block,
                                                 org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        WorldAccessGuard.checkMainOnlyWrite((Level) (Object) this, pos);
    }
}
