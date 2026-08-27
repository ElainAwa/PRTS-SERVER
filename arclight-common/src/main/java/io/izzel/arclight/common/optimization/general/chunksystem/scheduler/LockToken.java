/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 *
 * This file adapts code from FlowSched by ishland (RelativityMC)
 * (https://github.com/RelativityMC/FlowSched), licensed under MIT.
 * Original code Copyright (c) RelativityMC.
 */

package io.izzel.arclight.common.optimization.general.chunksystem.scheduler;

/**
 * 锁令牌（FlowSched LockToken 移植）：同一时刻同一令牌只允许一个任务持有，
 * 冲突任务挂到该令牌的 listener 队列，释放时重新入队。靠 equals/hashCode 判重。
 */
public interface LockToken {
}
