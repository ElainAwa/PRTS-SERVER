/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.mixin.optimization.general.chunksystem;

import io.izzel.arclight.common.optimization.general.chunksystem.guards.ChunkIoEventCaptureBus;
import net.neoforged.bus.api.IEventBus;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * IO 反序列化事件捕获注入面（M2.2 四件套 ③，M2.3 修正）。
 *
 * <p>原方案对 {@code net.neoforged.bus.EventBus.post} 做 mixin 注入，
 * 但该类由 bootstrap 层在一切 arclight mixin 配置装配前加载，注入永不
 * 生效（{@code EventBusStats} javadoc 实证结论，M2.3 集成冒烟复核：
 * 捕获计数恒 0 且运行期无 mixin 报错）。
 *
 * <p>修正：{@code ChunkSerializer}（游戏层，mixin 可达）的 {@code read}
 * 内两处 {@code NeoForge.EVENT_BUS} 静态读重定向到
 * {@link ChunkIoEventCaptureBus#wrapIfCapturing}——IO 反序列化捕获作用域内
 * 返回包装总线（{@code post} 入主线程延迟队列），作用域外原样直通。
 * 事件只发射一次且落在主线程，与基线「主线程反序列化期间发射」语义等价。
 */
@Mixin(ChunkSerializer.class)
public abstract class ChunkSerializerMixin_IoEventCapture {

    @Redirect(method = "read",
            at = @At(value = "FIELD", opcode = Opcodes.GETSTATIC,
                    target = "Lnet/neoforged/neoforge/common/NeoForge;EVENT_BUS:Lnet/neoforged/bus/api/IEventBus;"))
    private static IEventBus prts$captureBus() {
        return ChunkIoEventCaptureBus.wrapIfCapturing(net.neoforged.neoforge.common.NeoForge.EVENT_BUS);
    }
}
