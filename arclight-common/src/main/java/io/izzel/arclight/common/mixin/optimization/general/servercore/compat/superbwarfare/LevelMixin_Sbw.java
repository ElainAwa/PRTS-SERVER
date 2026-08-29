package io.izzel.arclight.common.mixin.optimization.general.servercore.compat.superbwarfare;

import com.atsuishio.superbwarfare.entity.OBBEntity;
import com.atsuishio.superbwarfare.tools.OBB;
import io.izzel.arclight.common.mod.mixins.annotation.LoadIfMod;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.entity.LevelEntityGetter;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.function.Predicate;

/**
 * SBW §2.8：把模组 LevelMixin 的全维实体扫描替换为局部空间查询。
 *
 * 模组的 LevelMixin 在 {@code Level.getEntities(Entity, AABB, Predicate)} 的 RETURN 注入
 * 处理器中遍历维度内全部实体（getAll()）逐 OBB 做 SAT——成本 O(全维实体数 × OBB 数)
 * 每弹每 tick。本 mixin 与模组同目标类（Level），以 {@code priority=2000} 使 RETURN
 * 处理器先于模组（默认 1000）执行：完成局部查询后 {@code cir.cancel()} 跳过模组的
 * 全维扫描 handler（RETURN 注入点的 handler 按 priority 排序，cancel 停止后续 handler；
 * HEAD 注入点如 OwnershipGuard 的跨区读检查不受影响——已在方法体执行前完成）。
 *
 * 局部查询：查询盒膨胀 MAX_OBB_EXTENT 后走 vanilla section 空间索引取候选，再对原始盒
 * 做 OBB SAT 过滤。候选集是旧全维扫描的严格超集（任何 OBB 与查询盒相交 ⇒ 其所属实体
 * 位置距查询盒 ≤ 全模组最大 OBB 外延 40.3 格 < 48），命中结果不变。
 */
@LoadIfMod(modid = "superbwarfare", condition = LoadIfMod.ModCondition.PRESENT)
@Mixin(value = Level.class, priority = 2000)
public abstract class LevelMixin_Sbw {

    /**
     * 局部查询膨胀半径：全模组载具 OBB 定义的最大外延（实体原点到 OBB 表面最远距离，
     * kirov 飞艇 ≈ 40.3 格）加余量。模组新增更大载具时需同步增大。
     */
    private static final double MAX_OBB_EXTENT = 48.0;

    @Shadow
    protected abstract LevelEntityGetter<Entity> getEntities();

    @Inject(method = "getEntities(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;)Ljava/util/List;",
            at = @At("RETURN"), cancellable = true)
    private void arclight$sbwLocalObbQuery(Entity pEntity, AABB pBoundingBox,
                                           Predicate<? super Entity> pPredicate,
                                           CallbackInfoReturnable<List<Entity>> cir) {
        if (!(pEntity instanceof Projectile)) {
            return; // 非弹道查询不拦截，放行其它 handler
        }
        List<Entity> returnValue = cir.getReturnValue();
        if (returnValue == null) {
            return;
        }
        getEntities().get(pBoundingBox.inflate(MAX_OBB_EXTENT), entity -> {
            if (pPredicate.test(entity) && entity != pEntity
                    && entity instanceof OBBEntity obbEntity && !obbEntity.enableAABB()) {
                for (OBB obb : obbEntity.getOBBs()) {
                    if (OBB.isColliding(obb, pBoundingBox) && !returnValue.contains(entity)) {
                        returnValue.add(entity);
                    }
                }
            }
        });
        // 跳过模组 LevelMixin 的全维扫描 handler（本实现已覆盖其语义）
        cir.cancel();
    }
}
