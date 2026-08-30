package io.izzel.arclight.common.optimization.general.servercore.compat;

import java.util.concurrent.locks.ReentrantLock;

/** Sable rapier native 全局锁：step/tick 与 queryRope 必须串行。 */
public final class SableNativeLock {

    public static final ReentrantLock LOCK = new ReentrantLock();

    private SableNativeLock() {
    }
}
