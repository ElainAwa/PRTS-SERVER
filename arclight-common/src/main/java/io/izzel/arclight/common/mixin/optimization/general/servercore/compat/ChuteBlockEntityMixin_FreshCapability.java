/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.mixin.optimization.general.servercore.compat;

import com.simibubi.create.content.logistics.chute.ChuteBlockEntity;
import io.izzel.arclight.common.mod.mixins.annotation.LoadIfMod;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.capabilities.BlockCapabilityCache;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemStackHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * [修复] 溜槽提取在并行引擎下失效（树场/移动接口对接场景）。
 *
 * 根因（生产探针实证）：PortableStorageInterface 的 capability 在
 * startTransferringTo（Server thread，100 槽好 handler）与
 * stopTransferring（RegionTick worker，0 槽空 handler）间约 1-2 秒交替
 * （旋转树场每圈对接/断开）。ChuteBlockEntity 的 grabCapability 走持久
 * BlockCapabilityCache：cache 创建时若 PSI 恰为空 handler（0 槽
 * ItemStackHandler），之后 PSI 对接时在 worker 线程 invalidateCapabilities，
 * 通知不到溜槽的 cache → cache 恒返回 0 槽空 handler → handleInput 的
 * extractItem 抛 "Slot 0 not in valid range - [0,0)" → 溜槽永不提取。
 * 而直接 getCapability 查询（新建 cache）始终正确。
 *
 * 修复：grabCapability 从 cache 拿到 0 槽空 handler 时，判定为失效缓存，
 * 强制改用直接查询（绕开坏缓存；好窗口直接查询返回真 handler，坏窗口
 * 返回空 handler 与原语义一致）。
 */
@LoadIfMod(modid = "create", condition = LoadIfMod.ModCondition.PRESENT)
@Mixin(value = ChuteBlockEntity.class, remap = false)
public abstract class ChuteBlockEntityMixin_FreshCapability {

    private static final java.lang.reflect.Method GET_CAPABILITY;
    private static final Object ITEM_HANDLER_BLOCK_CAP;

    static {
        java.lang.reflect.Method method = null;
        Object cap = null;
        try {
            Class<?> blockCapCls = Class.forName("net.neoforged.neoforge.capabilities.BlockCapability");
            cap = Class.forName("net.neoforged.neoforge.capabilities.Capabilities$ItemHandler")
                    .getField("BLOCK").get(null);
            method = ServerLevel.class.getMethod("getCapability", blockCapCls,
                    net.minecraft.core.BlockPos.class, Object.class);
        } catch (Throwable ignored) {
        }
        GET_CAPABILITY = method;
        ITEM_HANDLER_BLOCK_CAP = cap;
    }

    @Redirect(method = "grabCapability",
            at = @At(value = "INVOKE",
                    target = "Lnet/neoforged/neoforge/capabilities/BlockCapabilityCache;getCapability()Ljava/lang/Object;"))
    private Object prts$bypassStaleCache(BlockCapabilityCache<IItemHandler, Direction> cache) {
        Object cached = cache.getCapability();
        if (cached instanceof ItemStackHandler handler && handler.getSlots() == 0) {
            // 0 槽 ItemStackHandler = PSI 空 handler 残留（失效缓存）
            // 直接查询：与 create 的 registerCapabilities provider 相同路径
            ChuteBlockEntity self = (ChuteBlockEntity) (Object) this;
            if (self.getLevel() instanceof ServerLevel level && GET_CAPABILITY != null) {
                try {
                    Object found = GET_CAPABILITY.invoke(level, ITEM_HANDLER_BLOCK_CAP,
                            self.getBlockPos().relative(Direction.UP), Direction.DOWN);
                    if (found != null) {
                        return found;
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        return cached;
    }
}
