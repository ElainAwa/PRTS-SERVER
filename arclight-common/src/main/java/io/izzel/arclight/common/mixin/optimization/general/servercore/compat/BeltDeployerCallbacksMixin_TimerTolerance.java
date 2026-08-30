/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.mixin.optimization.general.servercore.compat;

import com.simibubi.create.content.kinetics.deployer.BeltDeployerCallbacks;
import com.simibubi.create.content.kinetics.deployer.DeployerBlockEntity;
import io.izzel.arclight.common.mod.mixins.annotation.LoadIfMod;
import net.minecraft.util.Mth;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * [修复] create 传送带装配在并行引擎下"特定速度区间卡死/物品堆积"。
 *
 * 根因：BeltDeployerCallbacks.whenItemHeld 的激活条件是
 * {@code state==RETRACTING && timer==1000} 精确相等。timer 每 tick 减
 * getTimerSpeed()（=clamp(|speed|*2, 8, 512)，反编译字节码确认），
 * EXPANDING 分支写入 timer=1000 后仅 1 tick 内可被观察到（下一 tick 即减到
 * 1000-step；速度≥250 时 step≥500，回程仅 2 tick）。即每个 6 tick 循环只有
 * 1 tick 的命中窗口，且依赖"belt 回调先于 deployer tick 执行"的 tick 内排序。
 * 并行区 worker 之间无该排序保证（belt 与 deployer 分属不同 region worker 时
 * 交错随机/漂移），窗口错过即永久 HOLD → 物品堆积；速度改变循环周期与交错
 * 相位，故"断断续续的速度区间"触发（实测：速度 250 触发、其他速度正常）。
 *
 * 修复：把精确 ==1000 放宽为窗口 {@code [1000-step, 1000]}（回程前 2 tick 的
 * 前后值均落在窗口内，无论回调与 tick 交错顺序如何都必命中一次），并用
 * per-deployer 游戏刻守卫保证每周期至多激活一次（防止窗口加宽后同一周期内
 * 对同一物品重复加工 2 步）。激活次数/节奏与原版语义一致。
 *
 * 证据：/tmp/deployer.txt getTimerSpeed 字节码 = f2i(Mth.clamp(|speed|*2,8,512))；
 * tick 状态机 EXPANDING→(activate, timer=1000)→RETRACTING；whenItemHeld 字节码
 * 100-117 行 state==RETRACTING && timer!=1000 → skip activate。
 */
@LoadIfMod(modid = "create", condition = LoadIfMod.ModCondition.PRESENT)
@Mixin(value = BeltDeployerCallbacks.class, remap = false)
public abstract class BeltDeployerCallbacksMixin_TimerTolerance {

    @Redirect(method = "whenItemHeld",
            at = @At(value = "FIELD",
                    target = "Lcom/simibubi/create/content/kinetics/deployer/DeployerBlockEntity;timer:I"))
    private static int prts$tolerateTimerWindow(DeployerBlockEntity deployer) {
        // 原 ==1000 判定放宽为回程起始窗口 [1000-step, 1000]
        int timer = ((DeployerBlockEntityMixin_EnsurePlayer) (Object) deployer).prts$getTimer();
        if (deployer.getLevel() == null) {
            return timer;
        }
        float speed = deployer.getSpeed();
        int step = (int) Mth.clamp(Math.abs(speed) * 2.0F, 8.0F, 512.0F); // 与 getTimerSpeed 一致
        if (step > 0 && timer <= 1000 && timer >= 1000 - step) {
            DeployerBlockEntityMixin_EnsurePlayer acc = (DeployerBlockEntityMixin_EnsurePlayer) (Object) deployer;
            long now = deployer.getLevel().getGameTime();
            long last = acc.prts$getLastBeltActivationTick();
            if (now - last >= 2L) {
                acc.prts$setLastBeltActivationTick(now);
                return 1000; // 窗口命中 → 原 ==1000 比较通过 → activate
            }
            return 999; // 同周期已激活过 → 强制不命中，防重复加工
        }
        return timer; // 窗口外 → 维持原判定
    }
}
