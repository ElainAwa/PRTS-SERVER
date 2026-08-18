package io.izzel.arclight.neoforge.compat.tacz;

import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Dedicated runtime bridge for TACZ (Timeless &amp; Classics: Zero) gun-operatator compatibility on
 * Arclight. Everything is reflection so there is no hard compile dependency on the TACZ mod; when
 * TACZ is absent or its signatures change, these methods are no-ops. Log tag {@code [Arclight-TACZ]}.
 *
 * <p><b>Why this exists:</b> on 1.21.1 SkinsRestorer refreshes a player's skin by pushing a fake
 * {@code ClientboundRespawnPacket} (keep-data) to the client. The client no longer calls
 * {@code LocalPlayer#respawn()} for a same-level keep-data respawn, so TACZ's own client recovery
 * hook never runs, and TACZ only re-sends its per-player synced gun state when a value changes. The
 * respawn can swallow the one "changed to 0" push; afterwards nothing is dirty so the client is left
 * permanently reading a stale blocking state and every shot fails. {@link #resetAndResyncGunOperator}
 * re-initialises the operator and then directly re-pushes the full synced gun state once the client
 * has finished handling the respawn, un-sticking it.
 */
public final class TaczGunOperatorCompat {
    private static final Logger LOGGER = LogManager.getLogger("Arclight-TACZ");

    /** TACZ's outbound shoot payload channel ({@code tacz:client_player_shoot}), used for diagnostics. */
    public static final ResourceLocation SHOOT_CHANNEL =
            ResourceLocation.fromNamespaceAndPath("tacz", "client_player_shoot");

    private static final ScheduledExecutorService SCHEDULER =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "Arclight-TACZ-sync");
                t.setDaemon(true);
                return t;
            });

    private TaczGunOperatorCompat() {
    }

    /**
     * Re-initialises the player's TACZ gun operator ({@code IGunOperator#initialData()}) and then
     * schedules a full, direct re-sync of the player's synced gun data shortly after the respawn
     * packet was pushed to the client.
     */
    public static void resetAndResyncGunOperator(ServerPlayer player) {
        if (player == null || !player.isAlive()) {
            return;
        }
        String name = player.getName().getString();
        try {
            resetGunOperatorNow(player);
            LOGGER.info("[Arclight-TACZ] reset-operator ok player={} state={}", name, snapshot(player));
        } catch (Throwable t) {
            LOGGER.info("[Arclight-TACZ] reset-operator FAILED player={} cause={}", name, t);
        }
        scheduleSync(player, 200L);
    }

    private static void resetGunOperatorNow(LivingEntity player) throws Exception {
        Class<?> operatorClass = Class.forName("com.tacz.guns.api.entity.IGunOperator");
        Method fromLivingEntity = operatorClass.getMethod("fromLivingEntity", LivingEntity.class);
        Object operator = fromLivingEntity.invoke(null, player);
        operatorClass.getMethod("initialData").invoke(operator);
    }

    private static void scheduleSync(ServerPlayer player, long delayMs) {
        SCHEDULER.schedule(() -> {
            MinecraftServer server = player.getServer();
            if (server == null) {
                return;
            }
            server.execute(() -> {
                if (!player.isAlive() || player.connection == null) {
                    return;
                }
                if (reSendGunOperatorSync(player)) {
                    LOGGER.info("[Arclight-TACZ] re-push synced gun state ok player={} state={}",
                            player.getName().getString(), snapshot(player));
                }
            });
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Mirrors TACZ {@code SyncedEntityDataEvent.onPlayerJoinWorld}: gathers the entity's full synced
     * gun state and sends it directly to the player, bypassing the dirty-tracking sweep so it cannot
     * be missed even if no value changed.
     *
     * @return true if an update packet was sent.
     */
    private static boolean reSendGunOperatorSync(ServerPlayer player) {
        try {
            Class<?> sedClass = Class.forName("com.tacz.guns.entity.sync.core.SyncedEntityData");
            Object instance = sedClass.getMethod("instance").invoke(null);
            Object holder = sedClass.getMethod("getDataHolder", Entity.class).invoke(instance, (Entity) player);
            if (holder == null) {
                return false;
            }
            List<?> entries = (List<?>) holder.getClass().getMethod("gatherAll").invoke(holder);
            if (entries == null || entries.isEmpty()) {
                return false;
            }
            Class<?> msgClass = Class.forName("com.tacz.guns.network.message.ServerMessageUpdateEntityData");
            Object payload = msgClass.getConstructor(int.class, List.class).newInstance(player.getId(), entries);

            // The reflectively built payload implements CustomPacketPayload; wrap it in the vanilla
            // outbound packet and deliver straight to this player's connection.
            var p = (net.minecraft.network.protocol.common.custom.CustomPacketPayload) payload;
            player.connection.send(new ClientboundCustomPayloadPacket(p));
            return true;
        } catch (Throwable t) {
            LOGGER.info("[Arclight-TACZ] re-push FAILED player={} cause={}", player.getName().getString(), t);
            return false;
        }
    }

    /** Snapshot of the player's server-side synced TACZ gun cooldowns (diagnostics). */
    public static String snapshot(LivingEntity player) {
        try {
            long shoot = readCooldown(player, "SHOOT_COOL_DOWN_KEY");
            long melee = readCooldown(player, "MELEE_COOL_DOWN_KEY");
            long draw = readCooldown(player, "DRAW_COOL_DOWN_KEY");
            long sprint = readCooldown(player, "SPRINT_TIME_KEY");
            return "shootCD=" + shoot + " meleeCD=" + melee + " drawCD=" + draw + " sprint=" + sprint;
        } catch (Throwable t) {
            return "snapshot-unavailable: " + t;
        }
    }

    private static long readCooldown(LivingEntity player, String keyField) throws Exception {
        Class<?> keys = Class.forName("com.tacz.guns.entity.sync.ModSyncedEntityData");
        Field field = keys.getField(keyField);
        Object key = field.get(null);
        // getValue is a record method on SyncedDataKey<E extends Entity, T>: erasure takes Entity.
        Method getValue = key.getClass().getMethod("getValue", Entity.class);
        Object raw = getValue.invoke(key, player);
        if (raw instanceof Number n) {
            return n.longValue();
        }
        return -1;
    }
}