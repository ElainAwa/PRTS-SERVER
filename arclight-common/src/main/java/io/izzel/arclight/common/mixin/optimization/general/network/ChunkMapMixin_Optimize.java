package io.izzel.arclight.common.mixin.optimization.general.network;

import io.izzel.arclight.common.bridge.core.entity.player.ServerPlayerEntityBridge;
import io.izzel.arclight.common.bridge.core.world.server.ChunkMap_TrackedEntityBridge;
import io.izzel.arclight.common.mod.compat.ModIds;
import io.izzel.arclight.common.mod.mixins.annotation.LoadIfMod;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerPlayerConnection;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.Objects;
import java.util.Set;

// [PRTS 本服维护者改动 2026-07-21]
// 门控历史：原版 {C2ME, NOISIUM} ABSENT → 路线A临时移除 NOISIUM 恢复脏玩家优化 →
// 现因路线B（VMP AreaMap 空间化实体追踪）要 @Overwrite/重占 ChunkMap.tick() 同一方法，
// 两者互斥，故恢复 NOISIUM 门控让本份 optimizedTick 在 Noisium 在场时让位给路线B。
// 本服常驻 noisium-forge-2.3.0 且仅世界生成，不碰 tick()，故门控恢复后本份永不加载，
// 由路线B独占 tick()。若未来要切回路线A，把 B 的实验开关关掉、并将下方 NOISIUM 移除即可。
@LoadIfMod(modid = {ModIds.C2ME, ModIds.NOISIUM}, condition = LoadIfMod.ModCondition.ABSENT)
@Mixin(ChunkMap.class)
public class ChunkMapMixin_Optimize {

    // @formatter:off
    @Shadow @Final public Int2ObjectMap<ChunkMap.TrackedEntity> entityMap;
    @Shadow @Final public ServerLevel level;
    // @formatter:on

    @Inject(method = "tick()V", cancellable = true, at = @At("HEAD"))
    private void arclight$optimizedTick(CallbackInfo ci) {
        var list = new ArrayList<ChunkMap.TrackedEntity>(this.level.players().size());

        for (var trackedEntity : this.entityMap.values()) {
            var entity = ((ChunkMap_TrackedEntityBridge) trackedEntity).bridge$getEntity();
            if (entity instanceof ServerPlayer player && ((ServerPlayerEntityBridge) player).bridge$isTrackerDirty()) {
                list.add(trackedEntity);
                ((ServerPlayerEntityBridge) player).bridge$setTrackerDirty(false);
            }
            ((ChunkMap_TrackedEntityBridge) trackedEntity).bridge$getServerEntity().sendChanges();
        }

        for (var trackedEntity : this.entityMap.values()) {
            var entity = ((ChunkMap_TrackedEntityBridge) trackedEntity).bridge$getEntity();
            SectionPos lastSectionPos = ((ChunkMap_TrackedEntityBridge) trackedEntity).bridge$getLastSectionPos();
            SectionPos newSectionPos = SectionPos.of(entity);
            ((ChunkMap_TrackedEntityBridge) trackedEntity).bridge$setLastSectionPos(newSectionPos);
            if (entity instanceof ServerPlayer player) {
                for (var otherTracker : list) {
                    var other = (ServerPlayer) ((ChunkMap_TrackedEntityBridge) otherTracker).bridge$getEntity();
                    if (other.getId() > entity.getId()) {
                        trackedEntity.updatePlayer(other);
                        otherTracker.updatePlayer(player);
                    }
                }
            } else {
                boolean chunkChanged = !Objects.equals(lastSectionPos, newSectionPos);
                if (chunkChanged) {
                    trackedEntity.updatePlayers(this.level.players());
                } else {
                    for (var other : list) {
                        trackedEntity.updatePlayer((ServerPlayer) ((ChunkMap_TrackedEntityBridge) other).bridge$getEntity());
                    }
                }
            }
        }
        ci.cancel();
    }

    @Mixin(ChunkMap.TrackedEntity.class)
    public static class TrackedEntityMixin {

        @Redirect(method = "<init>", at = @At(value = "INVOKE", remap = false, target = "Lcom/google/common/collect/Sets;newIdentityHashSet()Ljava/util/Set;"))
        private Set<ServerPlayerConnection> arclight$useFastUtilSet() {
            return new ReferenceOpenHashSet<>();
        }
    }
}
