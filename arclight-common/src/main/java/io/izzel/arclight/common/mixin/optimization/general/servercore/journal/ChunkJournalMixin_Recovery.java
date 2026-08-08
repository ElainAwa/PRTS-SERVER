package io.izzel.arclight.common.mixin.optimization.general.servercore.journal;

import io.izzel.arclight.common.compat.prts.PRTSFeaturesConfig;
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
 * 由 prts-features.yml 的 reliable-chunk-save 门控，默认关闭零开销。
 */
@Mixin(MinecraftServer.class)
public abstract class ChunkJournalMixin_Recovery {

    @Shadow
    public abstract Iterable<ServerLevel> getAllLevels();

    @Unique
    private int prts$journalTick = 0;

    @Inject(method = "tickServer", at = @At("RETURN"))
    private void prts$journalTick(BooleanSupplier hasTimeLeft, CallbackInfo ci) {
        if (!PRTSFeaturesConfig.reliableChunkSave) {
            return;
        }
        int perTick = PRTSFeaturesConfig.journalChunksPerTick;
        if (ChunkJournal.isFlushing()) {
            ChunkJournal.flushTick(perTick);
            return;
        }
        int interval = Math.max(5, (int) PRTSFeaturesConfig.journalIntervalSeconds) * 20;
        if (++prts$journalTick % interval != 0) {
            return;
        }
        ChunkJournal.beginFlush(this.getAllLevels());
        ChunkJournal.flushTick(perTick);
    }

    @Inject(method = "createLevels", at = @At("RETURN"))
    private void prts$journalRecover(CallbackInfo ci) {
        if (!PRTSFeaturesConfig.reliableChunkSave) {
            return;
        }
        for (ServerLevel level : this.getAllLevels()) {
            ChunkJournal.recover(level);
        }
    }

    @Inject(method = "stopServer", at = @At("HEAD"))
    private void prts$journalClean(CallbackInfo ci) {
        if (!PRTSFeaturesConfig.reliableChunkSave) {
            return;
        }
        for (ServerLevel level : this.getAllLevels()) {
            ChunkJournal.markClean(level);
        }
    }
}
