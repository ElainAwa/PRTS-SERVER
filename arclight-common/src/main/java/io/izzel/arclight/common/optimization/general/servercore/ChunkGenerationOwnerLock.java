package io.izzel.arclight.common.optimization.general.servercore;

import net.minecraft.server.level.ServerLevel;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/** ChunkMap 生成管线的单一属主锁。 */
public final class ChunkGenerationOwnerLock {

    private static final ConcurrentHashMap<ServerLevel, ReentrantLock> LOCKS = new ConcurrentHashMap<>();

    private ChunkGenerationOwnerLock() {
    }

    public static ReentrantLock lock(ServerLevel level) {
        return LOCKS.computeIfAbsent(level, ignored -> new ReentrantLock());
    }
}
