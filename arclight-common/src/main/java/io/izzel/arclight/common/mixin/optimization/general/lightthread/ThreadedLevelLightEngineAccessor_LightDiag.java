/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.mixin.optimization.general.lightthread;

import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.util.thread.ProcessorMailbox;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 光链诊断访问面（风暴卡死排查）：读 {@code ThreadedLevelLightEngine} 私有状态，
 * 供 {@code /sc status} 输出活体诊断。{@code lightTasks} 字段注解处理器无法解析，
 * 改由 {@link LightChainDiag} 运行期反射读取。
 */
@Mixin(ThreadedLevelLightEngine.class)
public interface ThreadedLevelLightEngineAccessor_LightDiag {

    @Accessor("scheduled")
    AtomicBoolean prts$scheduled();

    @Accessor("taskMailbox")
    ProcessorMailbox<Runnable> prts$taskMailbox();
}
