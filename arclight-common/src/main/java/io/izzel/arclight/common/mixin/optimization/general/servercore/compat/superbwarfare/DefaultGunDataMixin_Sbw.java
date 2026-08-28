package io.izzel.arclight.common.mixin.optimization.general.servercore.compat.superbwarfare;

import com.atsuishio.superbwarfare.data.gun.DefaultGunData;
import io.izzel.arclight.common.mod.mixins.annotation.LoadIfMod;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

/**
 * SBW §2.11：弹体默认寿命缩短。
 *
 * 模组 {@code DefaultGunData.projectileLife} 默认 400 tick（20 秒）——1200RPM 高射速
 * 弹种下单玩家最多 400 发常驻在飞 × 50 人 = 2 万发，每发每 tick 付全量命中检测。
 * 覆盖 getter：默认值 400 → 60（3 秒，约 400 码有效射程外本就不可见）；显式设置值
 * （JSON 数据 / EmptyGunItem=0 等）原样保留——仅字段仍为默认 400 时生效，数据驱动
 * 语义不受影响（已核实 52 个枪械 JSON 均无 projectileLife 覆写）。
 */
@LoadIfMod(modid = "superbwarfare", condition = LoadIfMod.ModCondition.PRESENT)
@Mixin(value = DefaultGunData.class, remap = false)
public abstract class DefaultGunDataMixin_Sbw {

    @Shadow @Final private int projectileLife;

    /**
     * @reason 引擎侧弹体寿命默认值 400 → 60（S2.11 §2.11），显式值不受影响。
     * @author Arclight
     */
    @Overwrite
    public int getProjectileLife() {
        return this.projectileLife == 400 ? 60 : this.projectileLife;
    }
}
