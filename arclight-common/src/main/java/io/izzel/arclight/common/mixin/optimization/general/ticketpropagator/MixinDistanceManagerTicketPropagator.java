package io.izzel.arclight.common.mixin.optimization.general.ticketpropagator;

import io.izzel.arclight.common.optimization.general.ticketpropagator.Delayed8WayDistancePropagator2D;
import it.unimi.dsi.fastutil.longs.Long2IntLinkedOpenHashMap;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.DistanceManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Set;

/** Ports HariPlayer (VMP fork) ticketpropagator to Forge 1.20.1 (Mojang mappings). */
@Mixin(DistanceManager.class)
public abstract class MixinDistanceManagerTicketPropagator {

    private static final Logger LOGGER = LogManager.getLogger("PRTS-TP");

    @Shadow
    protected abstract ChunkHolder getChunk(long p_140817_);

    @Shadow
    protected abstract ChunkHolder updateChunkScheduling(long p_140780_, int p_140781_, ChunkHolder p_140782_, int p_140783_);

    @Shadow
    @Final
    private Set<ChunkHolder> chunksToUpdateFutures;

    @Unique
    protected Long2IntLinkedOpenHashMap ticketLevelUpdates;

    @Unique
    protected Delayed8WayDistancePropagator2D ticketLevelPropagator;

    @Unique
    private static int convertBetweenTicketLevels(final int level) {
        return ChunkLevel.MAX_LEVEL - level + 1;
    }

    @Unique
    protected final void updateTicketLevel(final long coordinate, final int ticketLevel) {
        if (ticketLevel > ChunkLevel.MAX_LEVEL) {
            this.ticketLevelPropagator.removeSource(coordinate);
        } else {
            this.ticketLevelPropagator.setSource(coordinate, convertBetweenTicketLevels(ticketLevel));
        }
    }

    @Inject(method = "<init>", at = @At("RETURN"))
    private void onInit(CallbackInfo ci) {
        this.ticketLevelUpdates = new Long2IntLinkedOpenHashMap() {
            @Override
            protected void rehash(int newN) {
                if (newN < this.n) {
                    return;
                }
                super.rehash(newN);
            }
        };
        this.ticketLevelPropagator = new Delayed8WayDistancePropagator2D(
                (long coordinate, byte oldLevel, byte newLevel) -> {
                    this.ticketLevelUpdates.putAndMoveToLast(coordinate, convertBetweenTicketLevels(newLevel));
                }
        );
        LOGGER.info("[PRTS-TP] ticketpropagator mixin active");
    }

    // Replace the vanilla immediate propagation (ticketTracker.update) inside the ticket bookkeeping methods.
    // require=1：Forge patch 改动调用结构时允许部分失配(WARN)而非启动崩服；至少 1 处命中保证核心生效
    @Redirect(method = {
            "addTicket(JLnet/minecraft/server/level/Ticket;)V",
            "removeTicket(JLnet/minecraft/server/level/Ticket;)V",
            "purgeStaleTickets",
            "removeTicketsOnClosing"
    }, at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/DistanceManager$ChunkTicketTracker;update(JIZ)V"), require = 1, expect = 4)
    private void redirectUpdate(DistanceManager.ChunkTicketTracker instance, long l, int i, boolean b) {
        this.updateTicketLevel(l, i);
    }

    // Replace the per-tick propagation. The return value semantics mirror vanilla runDistanceUpdates:
    @Redirect(method = "runAllUpdates", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/DistanceManager$ChunkTicketTracker;runDistanceUpdates(I)I"))
    private int redirectRunDistanceUpdates(DistanceManager.ChunkTicketTracker instance, int p_140878_) {
        boolean hasUpdates = this.ticketLevelPropagator.propagateUpdates();

        while (!this.ticketLevelUpdates.isEmpty()) {
            long key = this.ticketLevelUpdates.firstLongKey();
            int newLevel = this.ticketLevelUpdates.removeFirstInt();

            ChunkHolder holder = this.getChunk(key);
            int currentLevel = holder == null ? ChunkLevel.MAX_LEVEL + 1 : holder.getTicketLevel();
            if (newLevel == currentLevel) {
                continue;
            }

            holder = this.updateChunkScheduling(key, newLevel, holder, currentLevel);

            if (holder == null) {
                if (newLevel <= ChunkLevel.MAX_LEVEL) {
                    throw new IllegalStateException("Chunk holder not created");
                }
                continue;
            }

            // Mirror vanilla ChunkTicketTracker.setLevel: the holder must be queued so its futures advance.
            this.chunksToUpdateFutures.add(holder);
        }

        return hasUpdates ? Integer.MAX_VALUE - 1 : Integer.MAX_VALUE;
    }
}
