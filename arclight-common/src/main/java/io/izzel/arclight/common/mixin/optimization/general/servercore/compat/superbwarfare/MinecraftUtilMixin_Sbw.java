package io.izzel.arclight.common.mixin.optimization.general.servercore.compat.superbwarfare;

import com.atsuishio.superbwarfare.config.server.SyncConfig;
import com.atsuishio.superbwarfare.network.message.receive.EntityRelationSyncMessage;
import com.atsuishio.superbwarfare.network.message.receive.PlayerInfoSyncMessage;
import com.atsuishio.superbwarfare.network.message.receive.VehicleShootClientMessage;
import com.atsuishio.superbwarfare.tools.MinecraftUtil;
import io.izzel.arclight.common.mod.mixins.annotation.LoadIfMod;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

import java.util.UUID;

/**
 * SBW 网络发送路径的两处引擎侧优化：
 *
 * <p><b>§2.3+§2.4 IFF/友军 ID 包节流（sendPacketTo）</b>：IffItem（主线程
 * ServerTickEvent，O(P²)）与载具/导弹/雷达（O(V×P)）的敌我识别包默认每 tick 全量
 * 发送。覆盖为 3 tick 节流——仅当 mod 配置 {@code sync_entity_interval} 仍为默认值 1
 * 时生效（用户显式调大过间隔则完全尊重，不叠加）。其余 payload 类型（音效/指示器/
 * 运动同步/超视距实体等）原样放行，逐字节等价。</p>
 *
 * <p><b>§2.1 载具射击广播 → tracking（sendPacketToAll）</b>：VehicleShootClientMessage
 * 每发一发炮弹都向全体玩家广播（750 包/s/辆 × 10 辆）。覆盖为只发给追踪该车辆实体的
 * 玩家（视距内），车辆找不到（已卸载/销毁）时降级原样全服广播兜底。其余 payload
 * 类型（TDM 同步/爆炸等）原样 sendToAllPlayers。VehicleShootClientMessage 的 vehicle
 * 字段是 typealias 后的裸 UUID，编译期直接强转调用 getter，零反射。</p>
 *
 * <p>两处都只覆盖 SBW 自己的工具类（其它模组不经过此方法），@LoadIfMod 保证无 SBW
 * 时本类不加载。</p>
 */
@LoadIfMod(modid = "superbwarfare", condition = LoadIfMod.ModCondition.PRESENT)
@Mixin(value = MinecraftUtil.class, remap = false)
public abstract class MinecraftUtilMixin_Sbw {

    /** IFF 包节流间隔（tick）：默认 1 tick → 3 tick，包量 ÷3。 */
    private static final int IFF_THROTTLE_TICKS = 3;

    /**
     * @reason 引擎侧 IFF/友军 ID 包节流（S2.11 §2.3/§2.4）：默认配置下每 3 tick 放行，
     *         其余 payload 原样转发；mod 的 sync_entity_interval 被用户调大过则完全不干预。
     * @author Arclight
     */
    @Overwrite
    public static void sendPacketTo(Player player, CustomPacketPayload packet) {
        if (player instanceof ServerPlayer serverPlayer) {
            if ((packet instanceof EntityRelationSyncMessage || packet instanceof PlayerInfoSyncMessage)
                    && SyncConfig.SYNC_ENTITY_INTERVAL.get().intValue() == 1
                    && serverPlayer.getServer().getTickCount() % IFF_THROTTLE_TICKS != 0) {
                return;
            }
            PacketDistributor.sendToPlayer(serverPlayer, packet);
        }
    }

    /**
     * @reason 引擎侧载具射击包 tracking 化（S2.11 §2.1）：VehicleShootClientMessage
     *         改发追踪实体玩家（视距内），其余 payload 原样全服广播。
     * @author Arclight
     */
    @Overwrite
    public static void sendPacketToAll(CustomPacketPayload packet) {
        if (packet instanceof VehicleShootClientMessage shoot) {
            UUID vehicleId = shoot.getVehicle();
            if (vehicleId != null) {
                MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
                if (server != null) {
                    for (ServerLevel level : server.getAllLevels()) {
                        Entity vehicle = level.getEntities().get(vehicleId);
                        if (vehicle != null) {
                            PacketDistributor.sendToPlayersTrackingEntity(vehicle, packet);
                            return;
                        }
                    }
                }
            }
        }
        PacketDistributor.sendToAllPlayers(packet);
    }
}
