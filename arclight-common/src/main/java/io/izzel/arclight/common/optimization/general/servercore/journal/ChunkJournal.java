package io.izzel.arclight.common.optimization.general.servercore.journal;

import io.izzel.arclight.common.bridge.core.world.server.ChunkMapBridge;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import net.minecraft.world.level.chunk.storage.ChunkStorage;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * 可靠区块保存（journal 模式，features.reliable-chunk-save）。
 * 每周期把脏区块序列化写入 journal/&lt;world&gt;.jrn 并 force 落盘；非正常退出后启动回放。
 * 分片 flush：脏区块收集与序列化跨 tick 摊平，避免超大地图单 tick 卡死。
 * 回放安全：① WAL 权威比较——按 LastUpdate 仅当 journal 比 region 更新才写回，防旧快照覆盖新数据；
 * ② 两阶段删除——回放后 rename .jrn→.jrn.applied 保留证据，验证窗口(writeAll 周期)后 cleanupApplied 删除。
 */
public final class ChunkJournal {

    private static final Logger LOGGER = LogManager.getLogger("PRTS-Journal");
    private static final String MAGIC = "PRTSJRNL";
    private static final int VERSION = 1;
    private static final Path DIR = Path.of("journal");

    private static final List<PendingLevel> PENDING = new ArrayList<>();
    private static boolean flushing = false;

    private ChunkJournal() {
    }

    /** 是否正处于分片 flush 过程中。 */
    public static boolean isFlushing() {
        return flushing;
    }

    /** 周期开始：轻量收集所有维度脏区块引用（不序列化）。主线程调用。 */
    public static void beginFlush(Iterable<ServerLevel> levels) {
        PENDING.clear();
        flushing = false;
        for (ServerLevel level : levels) {
            PendingLevel pl = new PendingLevel(level);
            ServerChunkCache cache = (ServerChunkCache) level.getChunkSource();
            for (ChunkHolder holder : ((ChunkMapBridge) cache.chunkMap).bridge$getLoadedChunksIterable()) {
                ChunkAccess chunk = holder.getFullChunk();
                if (chunk != null && chunk.isUnsaved()) {
                    pl.dirty.add(new Entry(chunk.getPos(), chunk, null));
                }
            }
            if (!pl.dirty.isEmpty()) {
                PENDING.add(pl);
            }
        }
        flushing = !PENDING.isEmpty();
    }

    /** 每 tick 序列化最多 perTick 个脏区块；全部完成后写盘。主线程调用。 */
    public static void flushTick(int perTick) {
        if (!flushing) {
            return;
        }
        int budget = Math.max(1, perTick);
        for (PendingLevel pl : PENDING) {
            while (budget > 0 && !pl.dirty.isEmpty()) {
                Entry e = pl.dirty.remove(pl.dirty.size() - 1);
                budget--;
                if (!e.chunk.isUnsaved()) {
                    continue; // 已被原版增量保存，跳过避免旧快照覆盖新数据
                }
                try {
                    e.tag = ChunkSerializer.write(pl.level, e.chunk);
                    pl.done.add(e);
                } catch (Exception ex) {
                    LOGGER.warn("[PRTS-Journal] serialize {} failed: {}", e.pos, ex.toString());
                }
            }
            if (budget <= 0) {
                break;
            }
        }
        boolean allDone = true;
        for (PendingLevel pl : PENDING) {
            if (!pl.dirty.isEmpty()) {
                allDone = false;
                break;
            }
        }
        if (allDone) {
            writeAll();
            PENDING.clear();
            flushing = false;
        }
    }

    private static void writeAll() {
        try {
            Files.createDirectories(DIR);
            for (PendingLevel pl : PENDING) {
                if (pl.done.isEmpty()) {
                    continue;
                }
                Path tmp = DIR.resolve(name(pl.level) + ".tmp");
                Path target = DIR.resolve(name(pl.level));
                try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                    DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Channels.newOutputStream(ch)));
                    out.writeUTF(MAGIC);
                    out.writeInt(VERSION);
                    for (Entry e : pl.done) {
                        byte[] bytes = toBytes(e.tag);
                        out.writeInt(e.pos.x);
                        out.writeInt(e.pos.z);
                        out.writeInt(bytes.length);
                        out.write(bytes);
                    }
                    out.flush();
                    ch.force(true);
                }
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                LOGGER.info("[PRTS-Journal] flushed {} chunks -> {}", pl.done.size(), target);
            }
            cleanupApplied();
        } catch (IOException e) {
            LOGGER.warn("[PRTS-Journal] flush failed: {}", e.toString());
        }
    }

    private static byte[] toBytes(CompoundTag tag) throws IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        NbtIo.write(tag, new DataOutputStream(bos));
        return bos.toByteArray();
    }

    /** 启动回放：journal 残留（上次非正常退出）→ 按 LastUpdate 权威比较写回 region → rename 为 .applied 保留证据。 */
    public static void recover(ServerLevel level) {
        Path jrn = DIR.resolve(name(level));
        Path applied = DIR.resolve(name(level) + ".applied");
        Path source;
        boolean wasJournal;
        if (Files.exists(jrn)) {
            source = jrn;
            wasJournal = true;
        } else if (Files.exists(applied)) {
            // 上次已回放但未过验证窗口又崩：.applied 等同 .jrn，幂等重放，保留不删
            source = applied;
            wasJournal = false;
        } else {
            return;
        }
        int n = 0;
        try {
            ChunkStorage storage = (ChunkStorage) ((ServerChunkCache) level.getChunkSource()).chunkMap;
            try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(source)))) {
                if (!MAGIC.equals(in.readUTF()) || in.readInt() != VERSION) {
                    LOGGER.warn("[PRTS-Journal] {}: bad header, dropped", source);
                    Files.delete(source);
                    return;
                }
                while (true) {
                    int x;
                    int z;
                    int len;
                    try {
                        x = in.readInt();
                        z = in.readInt();
                        len = in.readInt();
                    } catch (EOFException eof) {
                        break;
                    }
                    if (len < 0 || len > 64 * 1024 * 1024) {
                        LOGGER.warn("[PRTS-Journal] {}: corrupt entry at x={} z={}, rest dropped", source, x, z);
                        break;
                    }
                    byte[] bytes = new byte[len];
                    in.readFully(bytes);
                    CompoundTag tag = NbtIo.read(new DataInputStream(new ByteArrayInputStream(bytes)));
                    if (tag != null && shouldReplay(storage, new ChunkPos(x, z), tag)) {
                        storage.write(new ChunkPos(x, z), tag);
                        n++;
                    }
                }
            }
            if (wasJournal) {
                // 两阶段删除：先原子 rename 保留证据，验证窗口(writeAll 周期)后再清
                try {
                    Files.deleteIfExists(applied);
                    Files.move(source, applied, StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException e) {
                    LOGGER.warn("[PRTS-Journal] rename {} failed, deleting: {}", source, e.toString());
                    try {
                        Files.delete(source);
                    } catch (IOException ignored) {
                    }
                }
            }
            LOGGER.info("[PRTS-Journal] recovered {} chunks -> {}", n, level.dimension().location());
        } catch (IOException e) {
            LOGGER.warn("[PRTS-Journal] recover failed ({}): {}", level.dimension().location(), e.toString());
        }
    }

    /** WAL 权威比较：仅当 journal 快照比 region 现有数据更新时才回放，避免旧快照覆盖新数据。 */
    private static boolean shouldReplay(ChunkStorage storage, ChunkPos pos, CompoundTag journalTag) {
        try {
            CompoundTag existing = storage.read(pos).join().orElse(null);
            if (existing == null) {
                return true; // region 缺失 → 应用 journal
            }
            long journalLU = getLastUpdate(journalTag);
            long regionLU = getLastUpdate(existing);
            return journalLU > regionLU; // 仅当 journal 更新才覆盖
        } catch (Exception e) {
            LOGGER.warn("[PRTS-Journal] read region for compare failed, conservative replay: {}", e.toString());
            return true;
        }
    }

    /** LastUpdate = ChunkSerializer 序列化时刻的全局 gameTime，天然单调新鲜度标尺。 */
    private static long getLastUpdate(CompoundTag tag) {
        return tag.getLong("LastUpdate");
    }

    /** 验证窗口后清理上轮 .applied：此时新一轮 journal 已 force 落盘、region 持久化安全。 */
    private static void cleanupApplied() {
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(DIR, "*.jrn.applied")) {
            for (Path p : ds) {
                try {
                    Files.delete(p);
                } catch (IOException ignored) {
                }
            }
        } catch (IOException ignored) {
        }
    }

    /** 正常关服：删除 journal 与 .applied（双保险）。 */
    public static void markClean(ServerLevel level) {
        try {
            Files.deleteIfExists(DIR.resolve(name(level)));
            Files.deleteIfExists(DIR.resolve(name(level) + ".applied"));
        } catch (IOException ignored) {
        }
    }

    private static String name(ServerLevel level) {
        return level.dimension().location().toString().replace(':', '_') + ".jrn";
    }

    private static final class PendingLevel {
        final ServerLevel level;
        final List<Entry> dirty = new ArrayList<>();
        final List<Entry> done = new ArrayList<>();

        PendingLevel(ServerLevel level) {
            this.level = level;
        }
    }

    private static final class Entry {
        final ChunkPos pos;
        final ChunkAccess chunk;
        CompoundTag tag;

        Entry(ChunkPos pos, ChunkAccess chunk, CompoundTag tag) {
            this.pos = pos;
            this.chunk = chunk;
            this.tag = tag;
        }
    }
}
