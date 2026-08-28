/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.mixin.optimization.general.servercore.compat;

import com.simibubi.create.content.kinetics.KineticNetwork;
import io.izzel.arclight.common.mod.mixins.annotation.LoadIfMod;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.locks.ReentrantLock;

/**
 * Create 的 KineticNetwork 是每维度全局可变结构（sources/members 两张 Map
 * 与 capacity/stress 字段），被维度 worker 的方块实体 tick 与主线程玩家交互
 * 并发读写，造成状态损坏。为每个网络实例加一把锁，通过 HEAD/RETURN 双注入
 * 串行化这些入口，避免并发损坏。仅 Create 加载时生效，单线程下无额外开销。
 */
@LoadIfMod(modid = "create", condition = LoadIfMod.ModCondition.PRESENT)
@Mixin(value = KineticNetwork.class, remap = false)
public abstract class KineticNetworkMixin_Create {

    @Unique
    private final ReentrantLock arclight$kineticLock = new ReentrantLock();

    @Inject(method = "initFromTE", at = @At("HEAD"), remap = false)
    private void arclight$lockInitFromTE(CallbackInfo ci) {
        this.arclight$kineticLock.lock();
    }

    @Inject(method = "initFromTE", at = @At("RETURN"), remap = false)
    private void arclight$unlockInitFromTE(CallbackInfo ci) {
        this.arclight$kineticLock.unlock();
    }

    @Inject(method = "addSilently", at = @At("HEAD"), remap = false)
    private void arclight$lockAddSilently(CallbackInfo ci) {
        this.arclight$kineticLock.lock();
    }

    @Inject(method = "addSilently", at = @At("RETURN"), remap = false)
    private void arclight$unlockAddSilently(CallbackInfo ci) {
        this.arclight$kineticLock.unlock();
    }

    @Inject(method = "add", at = @At("HEAD"), remap = false)
    private void arclight$lockAdd(CallbackInfo ci) {
        this.arclight$kineticLock.lock();
    }

    @Inject(method = "add", at = @At("RETURN"), remap = false)
    private void arclight$unlockAdd(CallbackInfo ci) {
        this.arclight$kineticLock.unlock();
    }

    @Inject(method = "updateCapacityFor", at = @At("HEAD"), remap = false)
    private void arclight$lockUpdateCapacityFor(CallbackInfo ci) {
        this.arclight$kineticLock.lock();
    }

    @Inject(method = "updateCapacityFor", at = @At("RETURN"), remap = false)
    private void arclight$unlockUpdateCapacityFor(CallbackInfo ci) {
        this.arclight$kineticLock.unlock();
    }

    @Inject(method = "updateStressFor", at = @At("HEAD"), remap = false)
    private void arclight$lockUpdateStressFor(CallbackInfo ci) {
        this.arclight$kineticLock.lock();
    }

    @Inject(method = "updateStressFor", at = @At("RETURN"), remap = false)
    private void arclight$unlockUpdateStressFor(CallbackInfo ci) {
        this.arclight$kineticLock.unlock();
    }

    @Inject(method = "remove", at = @At("HEAD"), remap = false)
    private void arclight$lockRemove(CallbackInfo ci) {
        this.arclight$kineticLock.lock();
    }

    @Inject(method = "remove", at = @At("RETURN"), remap = false)
    private void arclight$unlockRemove(CallbackInfo ci) {
        this.arclight$kineticLock.unlock();
    }

    @Inject(method = "sync", at = @At("HEAD"), remap = false)
    private void arclight$lockSync(CallbackInfo ci) {
        this.arclight$kineticLock.lock();
    }

    @Inject(method = "sync", at = @At("RETURN"), remap = false)
    private void arclight$unlockSync(CallbackInfo ci) {
        this.arclight$kineticLock.unlock();
    }

    @Inject(method = "updateCapacity", at = @At("HEAD"), remap = false)
    private void arclight$lockUpdateCapacity(CallbackInfo ci) {
        this.arclight$kineticLock.lock();
    }

    @Inject(method = "updateCapacity", at = @At("RETURN"), remap = false)
    private void arclight$unlockUpdateCapacity(CallbackInfo ci) {
        this.arclight$kineticLock.unlock();
    }

    @Inject(method = "updateStress", at = @At("HEAD"), remap = false)
    private void arclight$lockUpdateStress(CallbackInfo ci) {
        this.arclight$kineticLock.lock();
    }

    @Inject(method = "updateStress", at = @At("RETURN"), remap = false)
    private void arclight$unlockUpdateStress(CallbackInfo ci) {
        this.arclight$kineticLock.unlock();
    }

    @Inject(method = "updateNetwork", at = @At("HEAD"), remap = false)
    private void arclight$lockUpdateNetwork(CallbackInfo ci) {
        this.arclight$kineticLock.lock();
    }

    @Inject(method = "updateNetwork", at = @At("RETURN"), remap = false)
    private void arclight$unlockUpdateNetwork(CallbackInfo ci) {
        this.arclight$kineticLock.unlock();
    }

    @Inject(method = "calculateCapacity", at = @At("HEAD"), remap = false)
    private void arclight$lockCalculateCapacity(CallbackInfoReturnable<Float> cir) {
        this.arclight$kineticLock.lock();
    }

    @Inject(method = "calculateCapacity", at = @At("RETURN"), remap = false)
    private void arclight$unlockCalculateCapacity(CallbackInfoReturnable<Float> cir) {
        this.arclight$kineticLock.unlock();
    }

    @Inject(method = "calculateStress", at = @At("HEAD"), remap = false)
    private void arclight$lockCalculateStress(CallbackInfoReturnable<Float> cir) {
        this.arclight$kineticLock.lock();
    }

    @Inject(method = "calculateStress", at = @At("RETURN"), remap = false)
    private void arclight$unlockCalculateStress(CallbackInfoReturnable<Float> cir) {
        this.arclight$kineticLock.unlock();
    }

    @Inject(method = "getActualCapacityOf", at = @At("HEAD"), remap = false)
    private void arclight$lockGetActualCapacityOf(CallbackInfoReturnable<Float> cir) {
        this.arclight$kineticLock.lock();
    }

    @Inject(method = "getActualCapacityOf", at = @At("RETURN"), remap = false)
    private void arclight$unlockGetActualCapacityOf(CallbackInfoReturnable<Float> cir) {
        this.arclight$kineticLock.unlock();
    }

    @Inject(method = "getActualStressOf", at = @At("HEAD"), remap = false)
    private void arclight$lockGetActualStressOf(CallbackInfoReturnable<Float> cir) {
        this.arclight$kineticLock.lock();
    }

    @Inject(method = "getActualStressOf", at = @At("RETURN"), remap = false)
    private void arclight$unlockGetActualStressOf(CallbackInfoReturnable<Float> cir) {
        this.arclight$kineticLock.unlock();
    }

    @Inject(method = "getSize", at = @At("HEAD"), remap = false)
    private void arclight$lockGetSize(CallbackInfoReturnable<Integer> cir) {
        this.arclight$kineticLock.lock();
    }

    @Inject(method = "getSize", at = @At("RETURN"), remap = false)
    private void arclight$unlockGetSize(CallbackInfoReturnable<Integer> cir) {
        this.arclight$kineticLock.unlock();
    }
}
