package io.izzel.arclight.common.optimization.general.servercore.compat;

import java.util.concurrent.locks.ReentrantLock;

/**
 * Create 动力网络拓扑全局锁（L1）。锁序固定 L1 → 网络实例锁（L2）。
 */
public final class KineticTopologyLock {

    public static final ReentrantLock LOCK = new ReentrantLock();

    private KineticTopologyLock() {
    }
}
