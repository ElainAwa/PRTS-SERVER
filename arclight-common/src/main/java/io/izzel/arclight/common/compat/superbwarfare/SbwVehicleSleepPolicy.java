package io.izzel.arclight.common.compat.superbwarfare;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import io.izzel.arclight.common.compat.prts.PRTSFeaturesConfig;
import io.izzel.arclight.common.optimization.general.servercore.RegionTickManager.VehicleSleepPolicy;
import net.minecraft.world.entity.Entity;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * SBW §2.16：空车休眠调度策略（插件式，由 {@link VehicleEntitySleepMixin_Sbw} 在
 * 载具首次实例化时注册到 {@code RegionTickManager}）。
 *
 * <p>判定条件（全部满足才跳过本 tick）：SBW 载具 ∧ 非残骸 ∧ 无乘客 ∧ 速度近零
 * （deltaMovement 模长平方 ≤ 0.01，即 ≤0.1 格/tick）。跳过期间实体完全不 tick——
 * §2.17-2.20 的每 tick 物理管线成本（碰撞/地形采样/OBB）全免；心跳间隔
 * （默认 10 tick = 0.5 秒）到达时恢复一次完整 tick，被推/上车/受击经心跳轮询
 * 在 0.5 秒内恢复（受击伤害本身直接走 hurt 调用路径，不依赖 tick）。</p>
 *
 * <p>唤醒语义核对：有乘客即恢复（乘骑 tick 由载具驱动，绝不能让睡车载乘客）；
 * 移动中不睡（deltaMovement 在物理 tick 内被碰撞收敛，静止判断天然有 1 tick
 * 确认窗口）；残骸不休眠（文档 §2.16 明确「非残骸」，残骸销毁倒计时等 tick 逻辑
 * 保持原样）。对非 SBW 实体与 SBW 外的模组零影响（instanceof 前置判定）。</p>
 */
public final class SbwVehicleSleepPolicy implements VehicleSleepPolicy {

    public static final SbwVehicleSleepPolicy INSTANCE = new SbwVehicleSleepPolicy();

    /** 静止判据：速度模长平方 ≤ 0.01（0.1 格/tick），0.1 格/tick = 2 格/秒以下视为静止。 */
    private static final double STILL_THRESHOLD_SQ = 0.01;

    /** 每实体最近一次真正 tick 的服务器 tick 号；弱引用，实体移除自动清理。 */
    private final Map<Entity, Long> lastTick = java.util.Collections.synchronizedMap(new WeakHashMap<>());

    private SbwVehicleSleepPolicy() {
    }

    @Override
    public boolean shouldSleepVehicle(Entity entity) {
        if (!PRTSFeaturesConfig.sbwVehicleSleep) {
            return false; // 默认关：未启用时零行为变化
        }
        if (!(entity instanceof VehicleEntity vehicle)) {
            return false;
        }
        if (vehicle.isWreck()) {
            return false; // §2.16：残骸不休眠（残骸销毁等 tick 逻辑保持）
        }
        if (!vehicle.getPassengers().isEmpty()) {
            return false; // 有乘客：乘骑 tick 由载具驱动，绝不休眠
        }
        if (vehicle.getDeltaMovement().lengthSqr() > STILL_THRESHOLD_SQ) {
            return false; // 移动中不睡（受击击退/被撞位移也会走此恢复）
        }
        long tick = vehicle.level().getServer() != null ? vehicle.level().getServer().getTickCount() : 0L;
        int interval = PRTSFeaturesConfig.sbwVehicleSleepInterval;
        synchronized (lastTick) {
            Long last = lastTick.get(entity);
            if (last == null || tick - last >= interval) {
                lastTick.put(entity, tick);
                return false; // 心跳点：本 tick 照常完整 tick 一次
            }
            return true; // 心跳间隔内：跳过本 tick（dispatch 不入队）
        }
    }
}
