/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.optimization.general.servercore;

import io.izzel.arclight.common.compat.prts.PRTSFeaturesConfig;
import io.izzel.arclight.common.optimization.general.servercore.ownership.ClassAffinityLedger;

/**
 * Three-tier block-entity scheduling policy. With region block-entity parallel
 * enabled, only registry keys on the allow list run on region workers; the force
 * list always stays on the main thread, and an allowed BE that triggers
 * main-thread-only world-access violations is automatically demoted to the main
 * thread by the shared violation ledger.
 */
public final class BlockEntityAffinity {

    /** BE 类型在 worker 上抛过任意异常（不只是世界访问违规）→ 本会话永久回主线程。 */
    private static final java.util.concurrent.ConcurrentHashMap<String, Boolean> UNSAFE =
            new java.util.concurrent.ConcurrentHashMap<>();

    private BlockEntityAffinity() {
    }

    /** True when this BE should tick on the owning region worker. */
    public static boolean shouldRunOnWorker(String typeKey, long tick) {
        if (matchesAny(typeKey, PRTSFeaturesConfig.beMainThreadForce)) {
            return false;
        }
        if (UNSAFE.containsKey(typeKey)) {
            return false;
        }
        if (!matchesAny(typeKey, PRTSFeaturesConfig.beParallelAllow)) {
            return false;
        }
        return !ClassAffinityLedger.shouldRouteMainThread("block-entity:" + typeKey, tick);
    }

    /** Marks a BE type as worker-unsafe for the rest of the server session. */
    public static void markUnsafe(String typeKey) {
        UNSAFE.put(typeKey, Boolean.TRUE);
    }

    /** One-line policy summary for /servercore status. */
    public static String statusText() {
        return "allow=" + PRTSFeaturesConfig.beParallelAllow.size()
                + " force=" + PRTSFeaturesConfig.beMainThreadForce.size()
                + " demoted=" + ClassAffinityLedger.routedCount("block-entity:")
                + " unsafe=" + UNSAFE.size();
    }

    private static boolean matchesAny(String key, java.util.List<String> patterns) {
        if (patterns == null || patterns.isEmpty()) {
            return false;
        }
        for (String pattern : patterns) {
            if (pattern.endsWith("*")) {
                if (key.startsWith(pattern.substring(0, pattern.length() - 1))) {
                    return true;
                }
            } else if (pattern.equals(key)) {
                return true;
            }
        }
        return false;
    }
}
