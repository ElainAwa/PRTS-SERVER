/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.neoforge.mixin.optimization.general.chunksystem;

import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.Block;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.List;

/**
 * NeoForge 掉落捕获线程安全修复：{@code Block.capturedDrops} 是普通静态字段
 * （非 ThreadLocal），维度/区域并行下方块事件在多个 worker 线程并发执行
 * {@code dropResources}（begin→popResource→stop）时互相覆盖/清空该字段，
 * 后结束的一方 {@code stopCapturingDrops} 拿到 null →
 * {@code CommonHooks.handleBlockDrops(..., null, ...)} → BlockDropsEvent
 * NPE 崩服（纯净服复飞 2026-08-25 22:26 实测：生长植物 tick → destroyBlock
 * → dropResources）。
 *
 * <p>修复：不替换任何方法体，仅把该静态字段的 GETSTATIC/PUTSTATIC 访问点
 * 重定向到本类 ThreadLocal——每个线程各自持有一份捕获列表，竞态消失。
 * 相比 @Overwrite begin/stop 的方案（实测 2026-08-25 22:40 部署后启动失败）：
 * 覆写会整体替换方法体，把核心层 {@code BlockMixin#arclight$captureDrops}
 * 注入 {@code popResource} 的 addFreshEntity 重定向一并抹掉 → 核心 mixin
 * APPLY 阶段找不到注入点直接崩启动；字段重定向不动方法体，注入点完好，
 * 且 begin/stop/popResource 三处逻辑逐字等价，Bukkit 侧捕获链不受影响。
 *
 * <p>放在 neoforge 模块：{@code capturedDrops} 是 NeoForge 补丁注入的成员，
 * common 模块对着原版编译不可见。
 */
@Mixin(Block.class)
public abstract class BlockMixin_ThreadLocalCapturedDrops {

    private static final ThreadLocal<List<ItemEntity>> prts$capturedDrops = new ThreadLocal<>();

    /**
     * 字段读点 → 按线程取捕获列表（捕获期外为 null，与原版语义一致）。
     * 覆盖 {@code popResource} 的判空/收集与 {@code stopCapturingDrops} 的取回。
     */
    @Redirect(method = {
        "popResource(Lnet/minecraft/world/level/Level;Ljava/util/function/Supplier;Lnet/minecraft/world/item/ItemStack;)V",
        "stopCapturingDrops"
    }, at = @At(value = "FIELD", opcode = Opcodes.GETSTATIC,
        target = "Lnet/minecraft/world/level/block/Block;capturedDrops:Ljava/util/List;"))
    private static List<ItemEntity> prts$getCapturedDrops() {
        return prts$capturedDrops.get();
    }

    /**
     * 字段写点 → 按线程存/清捕获列表。
     * 覆盖 {@code beginCapturingDrops} 的建表与 {@code stopCapturingDrops} 的置空。
     */
    @Redirect(method = {"beginCapturingDrops", "stopCapturingDrops"},
        at = @At(value = "FIELD", opcode = Opcodes.PUTSTATIC,
            target = "Lnet/minecraft/world/level/block/Block;capturedDrops:Ljava/util/List;"))
    private static void prts$setCapturedDrops(List<ItemEntity> value) {
        if (value == null) {
            prts$capturedDrops.remove();
        } else {
            prts$capturedDrops.set(value);
        }
    }
}
