/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.optimization.general.chunksystem;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.StringJoiner;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 区块系统任务栈诊断（M3，大文档 §4.4）：每线程 RunningWork 双端队列。
 *
 * <p>worker 执行任务前 {@link #push}、返回后 {@link #pop}（任务挂起等待依赖
 * 同样弹出——记录的是「该线程最后一次实际执行的同步段」）。崩溃报告经
 * {@code CrashReportMixin} 挂 {@link #dump()}；与现有抓包管线（jstack）互补：
 * jstack 只见 JVM 栈，任务栈直接给出「哪块哪个状态步」。
 */
public final class ChunkSystemThreadState {

    private static final ConcurrentHashMap<String, ArrayDeque<String>> RUNNING = new ConcurrentHashMap<>();

    private ChunkSystemThreadState() {
    }

    public static void push(String label) {
        RUNNING.computeIfAbsent(Thread.currentThread().getName(), k -> new ArrayDeque<>()).push(label);
    }

    public static void pop() {
        ArrayDeque<String> stack = RUNNING.get(Thread.currentThread().getName());
        if (stack != null) {
            stack.poll();
        }
    }

    /** 快照：{@code 线程名 -> 栈顶任务（<- 更早帧）}；无活跃任务时返回空串。 */
    public static String dump() {
        StringJoiner joiner = new StringJoiner("; ");
        for (Map.Entry<String, ArrayDeque<String>> entry : RUNNING.entrySet()) {
            StringJoiner frames = new StringJoiner(" <- ");
            for (String frame : entry.getValue()) {
                frames.add(frame);
            }
            if (frames.length() > 0) {
                joiner.add(entry.getKey() + " -> " + frames);
            }
        }
        return joiner.toString();
    }
}
