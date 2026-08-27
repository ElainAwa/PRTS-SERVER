/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.optimization.general.lightthread;

import com.mojang.datafixers.util.Pair;
import io.izzel.arclight.common.mixin.optimization.general.lightthread.ChunkMapAccessor_LightDiag;
import io.izzel.arclight.common.mixin.optimization.general.lightthread.ChunkTaskPriorityQueueAccessor_LightDiag;
import io.izzel.arclight.common.mixin.optimization.general.lightthread.ChunkTaskPriorityQueueSorterAccessor_LightDiag;
import io.izzel.arclight.common.mixin.optimization.general.lightthread.ThreadedLevelLightEngineAccessor_LightDiag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkTaskPriorityQueueSorter;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.util.thread.ProcessorHandle;
import net.minecraft.util.thread.ProcessorMailbox;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 光链活体诊断（风暴卡死排查）：把光照任务链各环状态打进 {@code /sc status}——
 * <ul>
 *   <li>{@code sched}：{@code tryScheduleUpdate} 的 scheduled CAS 标志（卡 true = 永不再排程）；</li>
 *   <li>{@code lt}：{@code lightTasks} 待处理列表大小；</li>
 *   <li>{@code lightMbox/sorterMbox}：邮箱 {@code toString}（status 位 + 队列空否）与深度；</li>
 *   <li>{@code queues}：排序器每个句柄的 {@code firstQueue/积压条目数}，{@code Z} = 该句柄
 *   在 sleeping 集合（{@code 积压>0 且 Z} = pop 链死亡/睡死，光照 future 永挂）；</li>
 *   <li>{@code pend}：全部更新中 holder 未完成 future 的状态索引直方图
 *   （如 {@code 9=32} 即 32 块卡在 light 步，直接钉死卡点层）。</li>
 * </ul>
 * 注意：本类不能放在 {@code io.izzel.arclight.common.mixin.*} 包下——
 * 该前缀归 mixin 配置所有，非 mixin 类被普通类加载会抛 IllegalClassLoadError。
 */
public final class LightChainDiag {

    /**
     * 注解处理器无法解析的三个私有字段（{@code mailbox}/{@code updatingChunkMap}/{@code lightTasks}），
     * 运行期反射读取；字段名已在运行期 jar（javap）确认存在。
     */
    private static final Field MAILBOX_FIELD = findField(ChunkTaskPriorityQueueSorter.class, "mailbox");
    private static final Field UPDATING_CHUNK_MAP_FIELD = findField(ChunkMap.class, "updatingChunkMap");
    private static final Field LIGHT_TASKS_FIELD = findField(ThreadedLevelLightEngine.class, "lightTasks");

    private static Field findField(Class<?> owner, String name) {
        try {
            Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (NoSuchFieldException e) {
            return null;
        }
    }

    private static Object read(Field field, Object target) {
        if (field == null) {
            return null;
        }
        try {
            return field.get(target);
        } catch (Throwable t) {
            return null;
        }
    }

    private LightChainDiag() {
    }

    public static String statusText(MinecraftServer server) {
        StringBuilder sb = new StringBuilder();
        for (ServerLevel level : server.getAllLevels()) {
            try {
                if (sb.length() > 0) {
                    sb.append(" | ");
                }
                sb.append(level.dimension().location().getPath()).append(':')
                        .append(describe(level));
            } catch (Throwable t) {
                sb.append(" err(").append(t.getClass().getSimpleName()).append(')');
            }
        }
        return sb.length() == 0 ? "no-levels" : sb.toString();
    }

    private static String describe(ServerLevel level) {
        ThreadedLevelLightEngine engine = level.getChunkSource().getLightEngine();
        ThreadedLevelLightEngineAccessor_LightDiag le = (ThreadedLevelLightEngineAccessor_LightDiag) (Object) engine;
        boolean sched = le.prts$scheduled().get();
        int lightTasks = read(LIGHT_TASKS_FIELD, engine) instanceof List<?> list ? list.size() : -1;
        ProcessorMailbox<Runnable> lightMbox = le.prts$taskMailbox();

        ChunkTaskPriorityQueueSorter sorter =
                ((ChunkMapAccessor_LightDiag) level.getChunkSource().chunkMap).prts$queueSorter();
        ChunkTaskPriorityQueueSorterAccessor_LightDiag sa =
                (ChunkTaskPriorityQueueSorterAccessor_LightDiag) sorter;
        Set<ProcessorHandle<?>> sleeping = sa.prts$sleeping();
        Object sorterMbox = read(MAILBOX_FIELD, sorter);

        StringBuilder queues = new StringBuilder();
        for (Map.Entry<ProcessorHandle<?>, Object> entry : sa.prts$queues().entrySet()) {
            ChunkTaskPriorityQueueAccessor_LightDiag pq =
                    (ChunkTaskPriorityQueueAccessor_LightDiag) entry.getValue();
            long backlog = 0;
            for (Object bucket : pq.prts$taskQueue()) {
                if (bucket instanceof Map<?, ?> map) {
                    backlog += map.size();
                }
            }
            queues.append(entry.getKey().name()).append('=')
                    .append(pq.prts$firstQueue()).append('/').append(backlog)
                    .append(sleeping.contains(entry.getKey()) ? "Z" : "").append(' ');
        }
        return " sched=" + sched + " lt=" + lightTasks
                + " lightMbox=[" + lightMbox + " sz=" + lightMbox.size() + "]"
                + " sorterMbox=[" + sorterMbox + (sorterMbox instanceof ProcessorMailbox<?> pm ? " sz=" + pm.size() : "") + "]"
                + " sleeping=" + sleeping.size()
                + " queues={" + queues.toString().trim() + "}"
                + " " + classifyHolders(level);
    }

    /**
     * holder 全分类诊断（钉死风暴冻结层）：遍历更新中 holder 的 {@code getAllFutures()}，
     * 每块按首个异常位分类：
     * <ul>
     *   <li>{@code ok}：到 FULL 全部 done 且成功；</li>
     *   <li>{@code pend=k}：首个未结算（非 null 且 !isDone）在状态索引 k（有驱动但永挂 = 死链）；</li>
     *   <li>{@code fail=k}：首个 done 但失败（UNLOADED 哨兵等）在 k（静默失败后无人重试）；</li>
     *   <li>{@code null=k}：前面全 done 但 k 起从未被请求（无驱动者 = 提交链丢请求）。</li>
     * </ul>
     * 另附最多 3 个非 ok holder 的 pos/票据级/任务目标取样。
     */
    private static String classifyHolders(ServerLevel level) {
        Object updating = read(UPDATING_CHUNK_MAP_FIELD, level.getChunkSource().chunkMap);
        if (!(updating instanceof Map<?, ?> holders)) {
            return "holders=n/a";
        }
        int total = 0, ok = 0;
        Map<Integer, Integer> pend = new TreeMap<>(), fail = new TreeMap<>(), nul = new TreeMap<>();
        StringBuilder sample = new StringBuilder();
        for (Object value : holders.values()) {
            if (!(value instanceof GenerationChunkHolder holder)) {
                continue;
            }
            total++;
            try {
                int firstPend = -1, firstFail = -1, firstNull = -1;
                for (Pair<ChunkStatus, ?> pair : holder.getAllFutures()) {
                    int idx = pair.getFirst().getIndex();
                    Object f = pair.getSecond();
                    if (f == null) {
                        firstNull = idx;
                        break;
                    }
                    if (!(f instanceof java.util.concurrent.CompletableFuture<?> future)) {
                        continue;
                    }
                    if (!future.isDone()) {
                        firstPend = idx;
                        break;
                    }
                    Object result = future.getNow(null);
                    if (!(result instanceof net.minecraft.server.level.ChunkResult<?> cr) || !cr.isSuccess()) {
                        firstFail = idx;
                        break;
                    }
                }
                if (firstPend >= 0) {
                    pend.merge(firstPend, 1, Integer::sum);
                } else if (firstFail >= 0) {
                    fail.merge(firstFail, 1, Integer::sum);
                    appendSample(sample, holder, "fail@" + firstFail);
                } else if (firstNull >= 0) {
                    nul.merge(firstNull, 1, Integer::sum);
                    appendSample(sample, holder, "null@" + firstNull);
                } else {
                    ok++;
                }
            } catch (Throwable ignored) {
                // 诊断读取失败不影响其余输出
            }
        }
        return "holders=" + total + " ok=" + ok
                + " pend=" + (pend.isEmpty() ? "{}" : pend.toString())
                + " fail=" + (fail.isEmpty() ? "{}" : fail.toString())
                + " never=" + (nul.isEmpty() ? "{}" : nul.toString())
                + (sample.length() > 0 ? " sample=[" + sample.substring(1) + "]" : "");
    }

    private static void appendSample(StringBuilder sample, GenerationChunkHolder holder, String tag) {
        if (sample.length() / 40 >= 3) {
            return;
        }
        sample.append(' ').append(holder.getPos()).append(':').append(tag);
        try {
            sample.append("/tl=").append(holder.getTicketLevel());
        } catch (Throwable ignored) {
            // 抽象方法实现缺失时跳过
        }
    }
}
