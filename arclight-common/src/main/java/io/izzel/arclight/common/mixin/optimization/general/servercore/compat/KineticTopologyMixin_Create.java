package io.izzel.arclight.common.mixin.optimization.general.servercore.compat;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.simibubi.create.content.kinetics.KineticNetwork;
import com.simibubi.create.content.kinetics.TorquePropagator;
import com.simibubi.create.content.kinetics.base.GeneratingKineticBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import io.izzel.arclight.common.mod.mixins.annotation.LoadIfMod;
import io.izzel.arclight.common.optimization.general.servercore.compat.KineticNetworkHealer;
import io.izzel.arclight.common.optimization.general.servercore.compat.KineticNetworkRepairBridge;
import io.izzel.arclight.common.optimization.general.servercore.compat.KineticTopologyLock;
import net.minecraft.world.level.LevelAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Create 动力网络拓扑串行化（L1 锁）。结构变更（挂接/拆分/建网）持有
 * KineticTopologyLock，网络内部计算仍只持实例锁（L2），锁序 L1 → L2。
 */
@LoadIfMod(modid = "create", condition = LoadIfMod.ModCondition.PRESENT)
public abstract class KineticTopologyMixin_Create {

    @Mixin(value = KineticBlockEntity.class, remap = false)
    public abstract static class KineticBlockEntityTopology {

        @Unique
        private int prts$healCooldown;

        @WrapMethod(method = "tick")
        private void prts$wrapTick(Operation<Void> original) {
            original.call();
            KineticBlockEntity self = (KineticBlockEntity) (Object) this;
            if (self.getLevel() == null || self.getLevel().isClientSide) {
                return;
            }
            // 自愈：成员网络无源时周期性触发原版重挂接，让相邻网络重新合并；
            // 首次错峰，避免加载完成瞬间全量方块实体同时取锁/BFS
            if (this.prts$healCooldown == 0) {
                this.prts$healCooldown = 1 + Math.floorMod(System.identityHashCode(this), 200);
                return;
            }
            if (--this.prts$healCooldown > 0) {
                return;
            }
            this.prts$healCooldown = 400;
            if (!self.hasNetwork()) {
                return;
            }
            KineticNetwork network = self.getOrCreateNetwork();
            if (network instanceof KineticNetworkRepairBridge bridge && bridge.prts$needsSourceHeal()) {
                // 安全愈合：先尝试有源侧 BFS；失败则整网原子清零重挂接——
                // 无源网络按 Create 语义应当停转（不显示过载）
                if (!KineticNetworkHealer.tryHeal(self)) {
                    bridge.prts$resetSourceLessNetwork();
                    this.prts$healCooldown = 200;
                }
            }
        }

        @WrapMethod(method = "setNetwork")
        private void prts$wrapSetNetwork(Long network, Operation<Void> original) {
            KineticTopologyLock.LOCK.lock();
            try {
                original.call(network);
            } finally {
                KineticTopologyLock.LOCK.unlock();
            }
        }

        @WrapMethod(method = "initialize")
        private void prts$wrapInitialize(Operation<Void> original) {
            KineticTopologyLock.LOCK.lock();
            try {
                original.call();
                // 存档里 Capacity=0 的坏网络：有源成员初始化后强制重算，避免缓存 0 延续
                KineticBlockEntity self = (KineticBlockEntity) (Object) this;
                if (self.getLevel() != null && !self.getLevel().isClientSide && self.hasNetwork()) {
                    KineticNetwork network = self.getOrCreateNetwork();
                    if (network instanceof KineticNetworkRepairBridge bridge) {
                        bridge.prts$repairCapacityIfNeeded();
                    }
                }
            } finally {
                KineticTopologyLock.LOCK.unlock();
            }
        }

        @WrapMethod(method = "remove")
        private void prts$wrapRemove(Operation<Void> original) {
            KineticTopologyLock.LOCK.lock();
            try {
                original.call();
            } finally {
                KineticTopologyLock.LOCK.unlock();
            }
        }

        @WrapMethod(method = "attachKinetics")
        private void prts$wrapAttachKinetics(Operation<Void> original) {
            KineticTopologyLock.LOCK.lock();
            try {
                original.call();
            } finally {
                KineticTopologyLock.LOCK.unlock();
            }
        }

        @WrapMethod(method = "detachKinetics")
        private void prts$wrapDetachKinetics(Operation<Void> original) {
            KineticTopologyLock.LOCK.lock();
            try {
                original.call();
            } finally {
                KineticTopologyLock.LOCK.unlock();
            }
        }
    }

    @Mixin(value = TorquePropagator.class, remap = false)
    public abstract static class TorquePropagatorTopology {

        @WrapMethod(method = "getOrCreateNetworkFor")
        private KineticNetwork prts$wrapGetOrCreateNetworkFor(KineticBlockEntity be,
                                                              Operation<KineticNetwork> original) {
            KineticTopologyLock.LOCK.lock();
            try {
                return original.call(be);
            } finally {
                KineticTopologyLock.LOCK.unlock();
            }
        }

        @WrapMethod(method = "onLoadWorld")
        private void prts$wrapOnLoadWorld(LevelAccessor world, Operation<Void> original) {
            KineticTopologyLock.LOCK.lock();
            try {
                original.call(world);
            } finally {
                KineticTopologyLock.LOCK.unlock();
            }
        }

        @WrapMethod(method = "onUnloadWorld")
        private void prts$wrapOnUnloadWorld(LevelAccessor world, Operation<Void> original) {
            KineticTopologyLock.LOCK.lock();
            try {
                original.call(world);
            } finally {
                KineticTopologyLock.LOCK.unlock();
            }
        }
    }

    @Mixin(value = GeneratingKineticBlockEntity.class, remap = false)
    public abstract static class GeneratorTopology {

        @WrapMethod(method = "updateGeneratedRotation")
        private void prts$wrapUpdateGeneratedRotation(Operation<Void> original) {
            KineticTopologyLock.LOCK.lock();
            try {
                original.call();
            } finally {
                KineticTopologyLock.LOCK.unlock();
            }
        }
    }
}
