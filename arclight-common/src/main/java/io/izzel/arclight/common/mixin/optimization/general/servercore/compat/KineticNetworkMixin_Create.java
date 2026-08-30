/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.mixin.optimization.general.servercore.compat;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.simibubi.create.content.kinetics.KineticNetwork;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import io.izzel.arclight.common.bridge.core.world.server.ServerChunkCacheRegionBridge;
import io.izzel.arclight.common.mod.mixins.annotation.LoadIfMod;
import io.izzel.arclight.common.optimization.general.servercore.DimensionTickManager;
import io.izzel.arclight.common.optimization.general.servercore.RegionTickManager;
import io.izzel.arclight.common.optimization.general.servercore.compat.KineticNetworkRepairBridge;
import io.izzel.arclight.common.optimization.general.servercore.compat.KineticTopologyLock;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * KineticNetwork 实例锁（L2）。所有方法 try/finally 解锁；remove 因其内部
 * 访问 TorquePropagator.networks 全局表，先取拓扑锁（L1）再取本锁。
 */
@LoadIfMod(modid = "create", condition = LoadIfMod.ModCondition.PRESENT)
@Mixin(value = KineticNetwork.class, remap = false)
public abstract class KineticNetworkMixin_Create implements KineticNetworkRepairBridge {

    @Unique
    private final ReentrantLock arclight$kineticLock = new ReentrantLock();

    @Shadow
    private float currentCapacity;

    @Shadow
    private float currentStress;

    @Shadow
    public Map<KineticBlockEntity, Float> sources;

    @Shadow
    public Map<KineticBlockEntity, Float> members;

    @WrapMethod(method = "initFromTE")
    private void arclight$wrapInitFromTE(float capacity, float stress, int size, Operation<Void> original) {
        this.arclight$kineticLock.lock();
        try {
            original.call(capacity, stress, size);
        } finally {
            this.arclight$kineticLock.unlock();
        }
    }

    @WrapMethod(method = "addSilently")
    private void arclight$wrapAddSilently(KineticBlockEntity be, float capacity, float stress,
                                          Operation<Void> original) {
        this.arclight$kineticLock.lock();
        try {
            original.call(be, capacity, stress);
        } finally {
            this.arclight$kineticLock.unlock();
        }
    }

    @WrapMethod(method = "add")
    private void arclight$wrapAdd(KineticBlockEntity be, Operation<Void> original) {
        this.arclight$kineticLock.lock();
        try {
            original.call(be);
        } finally {
            this.arclight$kineticLock.unlock();
        }
    }

    @WrapMethod(method = "updateCapacityFor")
    private void arclight$wrapUpdateCapacityFor(KineticBlockEntity be, float capacity,
                                                Operation<Void> original) {
        this.arclight$kineticLock.lock();
        try {
            original.call(be, capacity);
        } finally {
            this.arclight$kineticLock.unlock();
        }
    }

    @WrapMethod(method = "updateStressFor")
    private void arclight$wrapUpdateStressFor(KineticBlockEntity be, float stress,
                                              Operation<Void> original) {
        this.arclight$kineticLock.lock();
        try {
            original.call(be, stress);
        } finally {
            this.arclight$kineticLock.unlock();
        }
    }

    @WrapMethod(method = "remove")
    private void arclight$wrapRemove(KineticBlockEntity be, Operation<Void> original) {
        KineticTopologyLock.LOCK.lock();
        try {
            this.arclight$kineticLock.lock();
            try {
                original.call(be);
            } finally {
                this.arclight$kineticLock.unlock();
            }
        } finally {
            KineticTopologyLock.LOCK.unlock();
        }
    }

    @WrapMethod(method = "sync")
    private void arclight$wrapSync(Operation<Void> original) {
        this.arclight$kineticLock.lock();
        try {
            original.call();
        } finally {
            this.arclight$kineticLock.unlock();
        }
    }

    @WrapMethod(method = "updateCapacity")
    private void arclight$wrapUpdateCapacity(Operation<Void> original) {
        this.arclight$kineticLock.lock();
        try {
            original.call();
        } finally {
            this.arclight$kineticLock.unlock();
        }
    }

    @WrapMethod(method = "updateStress")
    private void arclight$wrapUpdateStress(Operation<Void> original) {
        this.arclight$kineticLock.lock();
        try {
            original.call();
        } finally {
            this.arclight$kineticLock.unlock();
        }
    }

    @WrapMethod(method = "updateNetwork")
    private void arclight$wrapUpdateNetwork(Operation<Void> original) {
        this.arclight$kineticLock.lock();
        try {
            original.call();
            // 修复性重算：有源有成员但缓存容量非正，直接重算并同步
            if (this.currentCapacity <= 0f && this.members.size() > 0 && this.sources.size() > 0) {
                ((KineticNetwork) (Object) this).updateCapacity();
            }
        } finally {
            this.arclight$kineticLock.unlock();
        }
    }

    @Unique
    private static boolean arclight$isWorker() {
        return RegionTickManager.isRegionWorker() || DimensionTickManager.isDimensionTickThread();
    }

    @Unique
    private boolean arclight$hasNonLiveMember(Iterable<KineticBlockEntity> tes) {
        for (KineticBlockEntity be : tes) {
            if (be.getLevel() instanceof ServerLevel level
                    && level.getChunkSource() instanceof ServerChunkCacheRegionBridge bridge
                    && !bridge.arclight$hasLiveChunk(be.getBlockPos().getX() >> 4, be.getBlockPos().getZ() >> 4)) {
                return true;
            }
        }
        return false;
    }

    @WrapMethod(method = "calculateCapacity")
    private float arclight$wrapCalculateCapacity(Operation<Float> original) {
        this.arclight$kineticLock.lock();
        try {
            // worker 上源区块未 FULL 时跳过重算：原版 identity 清理会误删源并缓存 0 容量
            if (arclight$isWorker() && arclight$hasNonLiveMember(this.sources.keySet())) {
                return this.currentCapacity;
            }
            return original.call();
        } finally {
            this.arclight$kineticLock.unlock();
        }
    }

    @WrapMethod(method = "calculateStress")
    private float arclight$wrapCalculateStress(Operation<Float> original) {
        this.arclight$kineticLock.lock();
        try {
            // 同上：跳过会误删成员的 identity 清理，保留上一缓存值
            if (arclight$isWorker() && arclight$hasNonLiveMember(this.members.keySet())) {
                return this.currentStress;
            }
            return original.call();
        } finally {
            this.arclight$kineticLock.unlock();
        }
    }

    @WrapMethod(method = "getActualCapacityOf")
    private float arclight$wrapGetActualCapacityOf(KineticBlockEntity be, Operation<Float> original) {
        this.arclight$kineticLock.lock();
        try {
            return original.call(be);
        } finally {
            this.arclight$kineticLock.unlock();
        }
    }

    @WrapMethod(method = "getActualStressOf")
    private float arclight$wrapGetActualStressOf(KineticBlockEntity be, Operation<Float> original) {
        this.arclight$kineticLock.lock();
        try {
            return original.call(be);
        } finally {
            this.arclight$kineticLock.unlock();
        }
    }

    @WrapMethod(method = "getSize")
    private int arclight$wrapGetSize(Operation<Integer> original) {
        this.arclight$kineticLock.lock();
        try {
            return original.call();
        } finally {
            this.arclight$kineticLock.unlock();
        }
    }

    @Override
    public boolean prts$repairCapacityIfNeeded() {
        this.arclight$kineticLock.lock();
        try {
            if (this.sources.isEmpty() || this.members.isEmpty() || this.currentCapacity > 0f) {
                return this.currentCapacity > 0f;
            }
            ((KineticNetwork) (Object) this).updateCapacity();
            return this.currentCapacity > 0f;
        } finally {
            this.arclight$kineticLock.unlock();
        }
    }

    @Override
    public boolean prts$needsSourceHeal() {
        this.arclight$kineticLock.lock();
        try {
            return this.sources.isEmpty() && !this.members.isEmpty();
        } finally {
            this.arclight$kineticLock.unlock();
        }
    }

    @Override
    public boolean prts$resetSourceLessNetwork() {
        KineticTopologyLock.LOCK.lock();
        try {
            this.arclight$kineticLock.lock();
            try {
                if (!this.sources.isEmpty() || this.members.isEmpty()) {
                    return false;
                }
                List<KineticBlockEntity> snapshot = new ArrayList<>(this.members.keySet());
                for (KineticBlockEntity be : snapshot) {
                    ((KineticNetwork) (Object) this).remove(be);
                    be.clearKineticInformation();
                    // 保持脱离：无源碎片终态是停转；等真正有源侧传播到来再重新挂接
                    be.updateSpeed = false;
                }
                return true;
            } finally {
                this.arclight$kineticLock.unlock();
            }
        } finally {
            KineticTopologyLock.LOCK.unlock();
        }
    }
}
