package io.izzel.arclight.common.optimization.general.servercore.compat;

import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import org.apache.logging.log4j.LogManager;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 分裂网络安全愈合：从有源侧 BFS，按 Create 的 conveyed speed 重写转速并迁移
 * network id。不经过 RotationPropagator 的 destroy 安全路径（分裂状态下符号
 * 已被污染，原版路径会误拆方块）。
 */
public final class KineticNetworkHealer {

    private static final Method GET_CONNECTED;
    private static final Method GET_CONVEYED;
    private static final int MAX_VISIT = 512;

    static {
        Method connected = null;
        Method conveyed = null;
        try {
            Class<?> rp = Class.forName("com.simibubi.create.content.kinetics.RotationPropagator");
            connected = rp.getDeclaredMethod("getConnectedNeighbours", KineticBlockEntity.class);
            connected.setAccessible(true);
            conveyed = rp.getDeclaredMethod("getConveyedSpeed", KineticBlockEntity.class, KineticBlockEntity.class);
            conveyed.setAccessible(true);
        } catch (Throwable t) {
            LogManager.getLogger("PRTS-Kinetic").warn("[kinetic-heal] reflection unavailable", t);
        }
        GET_CONNECTED = connected;
        GET_CONVEYED = conveyed;
    }

    private KineticNetworkHealer() {
    }

    public static boolean tryHeal(KineticBlockEntity start) {
        if (GET_CONNECTED == null || GET_CONVEYED == null || start.getLevel() == null) {
            return false;
        }
        KineticTopologyLock.LOCK.lock();
        try {
            // BFS 找有源锚点，IdentityHashMap 防 BE equals 重写
            Map<KineticBlockEntity, KineticBlockEntity> parent = new IdentityHashMap<>();
            Deque<KineticBlockEntity> queue = new ArrayDeque<>();
            Set<KineticBlockEntity> visited = Collections.newSetFromMap(new IdentityHashMap<>());
            queue.add(start);
            visited.add(start);
            KineticBlockEntity anchor = null;
            while (!queue.isEmpty() && visited.size() < MAX_VISIT) {
                KineticBlockEntity u = queue.poll();
                if (hasSources(u)) {
                    anchor = u;
                    break;
                }
                for (KineticBlockEntity v : connected(u)) {
                    if (v != null && visited.add(v)) {
                        parent.put(v, u);
                        queue.add(v);
                    }
                }
            }
            if (anchor == null) {
                return false;
            }
            if (anchor != start && parent.get(anchor) == null) {
                return false;
            }
            // 反推 start -> anchor 路径；从 anchor 向 start 逐跳迁移，
            // 保证每跳的父节点都已经属于有源网络
            List<KineticBlockEntity> path = new ArrayList<>();
            for (KineticBlockEntity cur = anchor; cur != null; cur = parent.get(cur)) {
                path.add(cur);
            }
            Collections.reverse(path);
            if (path.isEmpty() || path.get(0) != start) {
                return false;
            }
            for (int i = path.size() - 1; i >= 1; i--) {
                KineticBlockEntity child = path.get(i - 1);
                KineticBlockEntity next = path.get(i);
                migrate(child, next, conveyed(next, child));
            }
            // 继续 BFS 愈合整片无源网络
            queue.clear();
            visited.clear();
            queue.add(anchor);
            visited.add(anchor);
            while (!queue.isEmpty() && visited.size() < MAX_VISIT) {
                KineticBlockEntity u = queue.poll();
                for (KineticBlockEntity v : connected(u)) {
                    if (v == null || !visited.add(v)) {
                        continue;
                    }
                    if (!hasSources(v)) {
                        migrate(v, u, conveyed(u, v));
                    }
                    queue.add(v);
                }
            }
            if (anchor.getOrCreateNetwork() != null) {
                anchor.getOrCreateNetwork().updateNetwork();
            }
            return true;
        } finally {
            KineticTopologyLock.LOCK.unlock();
        }
    }

    @SuppressWarnings("unchecked")
    private static List<KineticBlockEntity> connected(KineticBlockEntity be) {
        try {
            Object result = GET_CONNECTED.invoke(null, be);
            if (result instanceof List<?> list) {
                return (List<KineticBlockEntity>) list;
            }
        } catch (Throwable ignored) {
        }
        return List.of();
    }

    private static float conveyed(KineticBlockEntity from, KineticBlockEntity to) {
        try {
            Object result = GET_CONVEYED.invoke(null, from, to);
            if (result instanceof Number number) {
                return number.floatValue();
            }
        } catch (Throwable ignored) {
        }
        return from.getTheoreticalSpeed();
    }

    private static boolean hasSources(KineticBlockEntity be) {
        if (be.getLevel() == null || be.getLevel().isClientSide) {
            return false;
        }
        if (!be.hasNetwork()) {
            return false;
        }
        var network = be.getOrCreateNetwork();
        return network instanceof KineticNetworkRepairBridge bridge && !bridge.prts$needsSourceHeal();
    }

    private static void migrate(KineticBlockEntity node, KineticBlockEntity parent, float speed) {
        float old = node.getSpeed();
        node.setSource(parent.getBlockPos());
        node.setSpeed(speed);
        node.onSpeedChanged(old);
        node.sendData();
    }
}
