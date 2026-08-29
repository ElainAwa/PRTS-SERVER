package io.izzel.arclight.common.mixin.optimization.general.servercore.compat.superbwarfare;

import com.atsuishio.superbwarfare.tools.ParticleTool;
import io.izzel.arclight.common.mod.mixins.annotation.LoadIfMod;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

/**
 * SBW §2.6：粒子发送距离裁剪。
 *
 * 模组 {@code ParticleTool.sendParticle}（11 参重载）无条件遍历 {@code level.players()}
 * 向全体玩家发粒子包——单次入水命中 135 次调用 × 50 人 = 6750 包。覆盖为按距离裁剪：
 * 只向距粒子 96 格内的玩家发送，包量从 O(P) 降到 O(近距玩家)。
 *
 * 语义核对：96 格外玩家本就看不清粒子，视觉差异可接受；爆炸/尾迹等半径更大的特效
 * 走各自消息（ExplosionParticleMessage 等），不受本覆盖影响。
 */
@LoadIfMod(modid = "superbwarfare", condition = LoadIfMod.ModCondition.PRESENT)
@Mixin(value = ParticleTool.class, remap = false)
public abstract class ParticleToolMixin_Sbw {

    /** 粒子发送距离上限（格），96 ≈ 6 个区块视距内的可见范围。 */
    private static final double PARTICLE_RADIUS_SQ = 96.0 * 96.0;

    /**
     * @reason 引擎侧粒子距离裁剪（S2.11 §2.6）：O(全体玩家) → O(96 格内玩家)。
     * @author Arclight
     */
    @Overwrite
    public static <T extends ParticleOptions> void sendParticle(
            ServerLevel level, T particle, double x, double y, double z, int count,
            double xOffset, double yOffset, double zOffset, double speed, boolean force) {
        for (ServerPlayer sp : level.players()) {
            double dx = sp.getX() - x;
            double dy = sp.getY() - y;
            double dz = sp.getZ() - z;
            if (dx * dx + dy * dy + dz * dz <= PARTICLE_RADIUS_SQ) {
                level.sendParticles(sp, particle, force, x, y, z, count, xOffset, yOffset, zOffset, speed);
            }
        }
    }
}
