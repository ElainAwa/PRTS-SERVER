/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 *
 * This file adapts code from ServerCore by Wesley1808
 * (https://github.com/Wesley1808/ServerCore), licensed under GPL-3.0.
 * Original code Copyright (c) Wesley1808.
 */

package io.izzel.arclight.common.mixin.optimization.general.servercore.journal;

import io.izzel.arclight.common.optimization.general.servercore.ServerCoreConfig;
import io.izzel.arclight.common.optimization.general.servercore.journal.ChunkJournal;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BooleanSupplier;

/**
 * journal 模式钩子：tick 周期 flush / 启动回放 / 正常关服清 journal。
 * 由 features.reliable-chunk-save 门控，默认关闭零开销。
 */
@Mixin(MinecraftServer.class)
public abstract class ChunkJournalMixin_Recovery {

    @Shadow
    public abstract Iterable<ServerLevel> getAllLevels();

    @Unique
    private int prts$journalTick = 0;

    @Inject(method = "tickServer", at = @At("RETURN"))
    private void prts$journalTick(BooleanSupplier hasTimeLeft, CallbackInfo ci) {
        if (!ServerCoreConfig.features().reliableChunkSave()) {
            return;
        }
        int perTick = ServerCoreConfig.features().journalChunksPerTick();
        if (ChunkJournal.isFlushing()) {
            ChunkJournal.flushTick(perTick);
            return;
        }
        int interval = Math.max(5, ServerCoreConfig.features().journalIntervalSeconds()) * 20;
        if (++prts$journalTick % interval != 0) {
            return;
        }
        ChunkJournal.beginFlush(this.getAllLevels());
        ChunkJournal.flushTick(perTick);
    }

    @Inject(method = "createLevels", at = @At("RETURN"))
    private void prts$journalRecover(CallbackInfo ci) {
        if (!ServerCoreConfig.features().reliableChunkSave()) {
            return;
        }
        for (ServerLevel level : this.getAllLevels()) {
            ChunkJournal.recover(level);
        }
    }

    @Inject(method = "stopServer", at = @At("HEAD"))
    private void prts$journalClean(CallbackInfo ci) {
        if (!ServerCoreConfig.features().reliableChunkSave()) {
            return;
        }
        for (ServerLevel level : this.getAllLevels()) {
            ChunkJournal.markClean(level);
        }
    }
}
