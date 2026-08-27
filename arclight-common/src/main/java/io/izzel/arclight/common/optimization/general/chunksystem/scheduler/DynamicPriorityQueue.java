/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 *
 * This file adapts code from FlowSched by ishland (RelativityMC)
 * (https://github.com/RelativityMC/FlowSched), licensed under MIT.
 * Original code Copyright (c) RelativityMC.
 */

package io.izzel.arclight.common.optimization.general.chunksystem.scheduler;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;

/**
 * 固定优先级数、支持改优先级的并发队列（FlowSched DynamicPriorityQueue 移植）。
 * 优先级数值越小越先出队；同一元素只允许入队一次（重复入队抛异常）。
 *
 * @param <E> 元素类型
 */
public class DynamicPriorityQueue<E> {

    private final AtomicIntegerArray taskCount;
    private final ConcurrentLinkedQueue<E>[] priorities;
    private final ConcurrentHashMap<E, Integer> priorityMap = new ConcurrentHashMap<>();

    public DynamicPriorityQueue(int priorityCount) {
        this.taskCount = new AtomicIntegerArray(priorityCount);
        //noinspection unchecked
        this.priorities = new ConcurrentLinkedQueue[priorityCount];
        for (int i = 0; i < priorityCount; i++) {
            this.priorities[i] = new ConcurrentLinkedQueue<>();
        }
    }

    public void enqueue(E element, int priority) {
        if (priority < 0 || priority >= priorities.length)
            throw new IllegalArgumentException("Priority out of range");
        if (this.priorityMap.putIfAbsent(element, priority) != null)
            throw new IllegalArgumentException("Element already in queue");

        this.priorities[priority].add(element);
        this.taskCount.incrementAndGet(priority);
    }

    // behavior is undefined when changing priority for one item concurrently
    public boolean changePriority(E element, int priority) {
        if (priority < 0 || priority >= priorities.length)
            throw new IllegalArgumentException("Priority out of range");

        int currentPriority = this.priorityMap.getOrDefault(element, -1);
        if (currentPriority == -1 || currentPriority == priority) {
            return false; // a clear failure
        }
        final boolean removedFromQueue = this.priorities[currentPriority].remove(element);
        if (!removedFromQueue) {
            return false; // the element is dequeued while we are changing priority
        }
        this.taskCount.decrementAndGet(currentPriority);
        final Integer put = this.priorityMap.put(element, priority);
        final boolean changeSuccess = put != null && put == currentPriority;
        if (!changeSuccess) {
            return false; // something else may have called remove()
        }
        this.priorities[priority].add(element);
        this.taskCount.incrementAndGet(priority);
        return true;
    }

    public E dequeue() {
        for (int i = 0; i < this.priorities.length; i++) {
            if (this.taskCount.get(i) == 0) continue;
            E element = priorities[i].poll();
            if (element != null) {
                this.taskCount.decrementAndGet(i);
                this.priorityMap.remove(element);
                return element;
            }
        }
        return null;
    }

    public boolean contains(E element) {
        return priorityMap.containsKey(element);
    }

    public void remove(E element) {
        final Integer remove = this.priorityMap.remove(element);
        if (remove == null) return;
        boolean removed = this.priorities[remove].remove(element); // best-effort
        if (removed) this.taskCount.decrementAndGet(remove);
    }

    public int size() {
        return priorityMap.size();
    }

}
