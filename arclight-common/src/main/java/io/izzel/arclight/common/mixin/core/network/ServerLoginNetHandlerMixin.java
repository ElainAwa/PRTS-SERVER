package io.izzel.arclight.common.mixin.core.network;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import io.izzel.arclight.common.bridge.core.network.NetworkManagerBridge;
import io.izzel.arclight.common.bridge.core.server.MinecraftServerBridge;
import io.izzel.arclight.common.bridge.core.server.management.PlayerListBridge;
import io.izzel.arclight.common.mod.velocity.VelocityForwarding;
import io.izzel.arclight.common.mod.velocity.VelocityManager;
import io.izzel.arclight.i18n.ArclightConfig;
import net.minecraft.DefaultUncaughtExceptionHandler;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundDisconnectPacket;
import net.minecraft.network.protocol.login.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import net.minecraft.util.Crypt;
import net.minecraft.util.CryptException;
import net.minecraftforge.fml.util.thread.SidedThreadGroups;
import org.apache.commons.lang3.Validate;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.v.CraftServer;
import org.bukkit.craftbukkit.v.util.Waitable;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerPreLoginEvent;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import javax.annotation.Nullable;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.security.PrivateKey;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static net.minecraft.server.network.ServerLoginPacketListenerImpl.isValidUsername;

@Mixin(ServerLoginPacketListenerImpl.class)
public abstract class ServerLoginNetHandlerMixin {

    @Shadow @Final private static AtomicInteger UNIQUE_THREAD_ID;
    @Shadow @Final private static Logger LOGGER;
    @Shadow @Final public Connection connection;
    // @formatter:off
    @Shadow private ServerLoginPacketListenerImpl.State state;
    @Shadow @Final private MinecraftServer server;
    @Shadow private GameProfile gameProfile;
    @Shadow private ServerPlayer delayedAcceptPlayer;
    @Shadow @Final private byte[] challenge;
    // Velocity Modern Forwarding support
    private int luminara$velocityLoginMessageId = -1;

    private static boolean arclight$validUsernameCheck(String name) {
        var regex = ArclightConfig.spec().getCompat().getValidUsernameRegex();
        return !regex.isBlank() && name.matches(regex);
    }

    @Shadow protected abstract GameProfile createFakeProfile(GameProfile original);
    // @formatter:on

    @Shadow public abstract void disconnect(Component reason);

    @Shadow public abstract String getUserName();

    public void disconnect(final String s) {
        this.disconnect(Component.literal(s));
    }

    /**
     * @author IzzelAliz
     * @reason
     */
    @Overwrite
    public void handleAcceptedLogin() {
        /*
        if (!this.loginGameProfile.isComplete()) {
            this.loginGameProfile = this.getOfflineProfile(this.loginGameProfile);
        }
        */

        if (!this.server.usesAuthentication()) {
            // this.gameProfile = this.createFakeProfile(this.gameProfile); // Spigot - Moved to initUUID
            // Spigot end
        }

        ServerPlayer entity = ((PlayerListBridge) this.server.getPlayerList()).bridge$canPlayerLogin(this.connection.getRemoteAddress(), this.gameProfile, (ServerLoginPacketListenerImpl) (Object) this);
        if (entity == null) {
            // this.disconnect(itextcomponent);
        } else {
            this.state = ServerLoginPacketListenerImpl.State.ACCEPTED;
            if (this.server.getCompressionThreshold() >= 0 && !this.connection.isMemoryConnection()) {
                this.connection.send(new ClientboundLoginCompressionPacket(this.server.getCompressionThreshold()), PacketSendListener.thenRun(() -> {
                    this.connection.setupCompression(this.server.getCompressionThreshold(), true);
                }));
            }

            this.connection.send(new ClientboundGameProfilePacket(this.gameProfile));
            ServerPlayer serverplayerentity = this.server.getPlayerList().getPlayer(this.gameProfile.getId());
            try {
                if (serverplayerentity != null) {
                    this.state = ServerLoginPacketListenerImpl.State.DELAY_ACCEPT;
                    this.delayedAcceptPlayer = entity;
                } else {
                    this.server.getPlayerList().placeNewPlayer(this.connection, entity);
                }
            } catch (Exception exception) {
                LOGGER.error("Couldn't place player in world", exception);
                var chatmessage = Component.translatable("multiplayer.disconnect.invalid_player_data");

                this.connection.send(new ClientboundDisconnectPacket(chatmessage));
                this.connection.disconnect(chatmessage);
            }
        }
    }

    /**
     * @author IzzelAliz
     * @reason
     */
    @Overwrite
    public void handleHello(ServerboundHelloPacket packetIn) {
        Validate.validState(this.state == ServerLoginPacketListenerImpl.State.HELLO, "Unexpected hello packet");
        Validate.validState(this.state == ServerLoginPacketListenerImpl.State.HELLO, "Unexpected hello packet");
        Validate.validState(arclight$validUsernameCheck(packetIn.name()) || isValidUsername(packetIn.name()), "Invalid characters in username");

        // Check for Velocity Modern Forwarding
        VelocityManager velocityManager = VelocityManager.getInstance();
        if (velocityManager.isVelocityForwardingEnabled()) {
            LOGGER.info("Velocity Modern Forwarding is enabled for player: {} (online-mode: {})",
                    packetIn.name(), velocityManager.getVelocityConfig().isOnlineMode());

            // Send Velocity query packet - following Mohist's approach
            this.luminara$velocityLoginMessageId = java.util.concurrent.ThreadLocalRandom.current().nextInt();

            // Create the query packet exactly like Mohist does
            try {
                // Create a simple buffer with just the supported version
                net.minecraft.network.FriendlyByteBuf queryBuf = new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
                queryBuf.writeByte(VelocityForwarding.MAX_SUPPORTED_FORWARDING_VERSION);

                // Create the custom query packet
                net.minecraft.network.protocol.login.ClientboundCustomQueryPacket queryPacket =
                        new net.minecraft.network.protocol.login.ClientboundCustomQueryPacket(
                                this.luminara$velocityLoginMessageId, VelocityForwarding.PLAYER_INFO_CHANNEL, queryBuf);

                this.connection.send(queryPacket);
                LOGGER.debug("Sent Velocity query packet with ID: {} for player: {}",
                        this.luminara$velocityLoginMessageId, packetIn.name());
                return; // Don't continue with normal login process
            } catch (Exception e) {
                LOGGER.error("Failed to send Velocity query packet", e);
                // Continue with normal login if we can't send the query
            }
        }

        GameProfile gameprofile = this.server.getSingleplayerProfile();
        if (gameprofile != null && packetIn.name().equalsIgnoreCase(gameprofile.getName())) {
            this.gameProfile = gameprofile;
            this.state = ServerLoginPacketListenerImpl.State.NEGOTIATING; // FORGE: continue NEGOTIATING, we move to READY_TO_ACCEPT after Forge is ready
        } else {
            this.gameProfile = new GameProfile(null, packetIn.name());
            if (this.server.usesAuthentication() && !this.connection.isMemoryConnection()) {
                this.state = ServerLoginPacketListenerImpl.State.KEY;
                this.connection.send(new ClientboundHelloPacket("", this.server.getKeyPair().getPublic().getEncoded(), this.challenge));
            } else {
                class Handler extends Thread {

                    Handler() {
                        super(SidedThreadGroups.SERVER, "User Authenticator #" + UNIQUE_THREAD_ID.incrementAndGet());
                    }

                    @Override
                    public void run() {
                        try {
                            initUUID();
                            arclight$preLogin();
                        } catch (Exception ex) {
                            disconnect("Failed to verify username!");
                            LOGGER.warn("Exception verifying {} ", gameProfile.getName(), ex);
                        }
                    }
                }
                new Handler().start();
            }
        }
    }

    public void initUUID() {
        UUID uuid;
        if (((NetworkManagerBridge) this.connection).bridge$getSpoofedUUID() != null) {
            uuid = ((NetworkManagerBridge) this.connection).bridge$getSpoofedUUID();
        } else {
            uuid = UUIDUtil.createOfflinePlayerUUID(this.gameProfile.getName());
        }
        this.gameProfile = new GameProfile(uuid, this.gameProfile.getName());
        if (((NetworkManagerBridge) this.connection).bridge$getSpoofedProfile() != null) {
            Property[] spoofedProfile;
            for (int length = (spoofedProfile = ((NetworkManagerBridge) this.connection).bridge$getSpoofedProfile()).length, i = 0; i < length; ++i) {
                final Property property = spoofedProfile[i];
                this.gameProfile.getProperties().put(property.getName(), property);
            }
        }
    }

    /**
     * @author IzzelAliz
     * @reason
     */
    @Overwrite
    public void handleKey(ServerboundKeyPacket packetIn) {
        Validate.validState(this.state == ServerLoginPacketListenerImpl.State.KEY, "Unexpected key packet");

        final String s;
        try {
            PrivateKey privatekey = this.server.getKeyPair().getPrivate();
            if (!packetIn.isChallengeValid(this.challenge, privatekey)) {
                throw new IllegalStateException("Protocol error");
            }

            SecretKey secretKey = packetIn.getSecretKey(privatekey);
            Cipher cipher = Crypt.getCipher(2, secretKey);
            Cipher cipher1 = Crypt.getCipher(1, secretKey);
            s = (new BigInteger(Crypt.digestData("", this.server.getKeyPair().getPublic(), secretKey))).toString(16);
            this.state = ServerLoginPacketListenerImpl.State.AUTHENTICATING;
            this.connection.setEncryptionKey(cipher, cipher1);
        } catch (CryptException cryptexception) {
            throw new IllegalStateException("Protocol error", cryptexception);
        }

        class Handler extends Thread {

            Handler(int i) {
                super(SidedThreadGroups.SERVER, "User Authenticator #" + i);
            }

            public void run() {
                GameProfile gameprofile = gameProfile;

                try {
                    gameProfile = server.getSessionService().hasJoinedServer(new GameProfile(null, gameprofile.getName()), s, this.getAddress());
                    if (gameProfile != null) {
                        if (!connection.isConnected()) {
                            return;
                        }
                        arclight$preLogin();
                    } else if (server.isSingleplayer()) {
                        LOGGER.warn("Failed to verify username but will let them in anyway!");
                        gameProfile = createFakeProfile(gameprofile);
                        state = ServerLoginPacketListenerImpl.State.NEGOTIATING;
                    } else {
                        disconnect(Component.translatable("multiplayer.disconnect.unverified_username"));
                        LOGGER.error("Username '{}' tried to join with an invalid session", gameprofile.getName());
                    }
                } catch (Exception var3) {
                    if (server.isSingleplayer()) {
                        LOGGER.warn("Authentication servers are down but will let them in anyway!");
                        gameProfile = createFakeProfile(gameprofile);
                        state = ServerLoginPacketListenerImpl.State.NEGOTIATING;
                    } else {
                        disconnect(Component.translatable("multiplayer.disconnect.authservers_down"));
                        LOGGER.error("Couldn't verify username because servers are unavailable");
                    }
                }

            }

            @Nullable
            private InetAddress getAddress() {
                SocketAddress socketaddress = connection.getRemoteAddress();
                return server.getPreventProxyConnections() && socketaddress instanceof InetSocketAddress ? ((InetSocketAddress) socketaddress).getAddress() : null;
            }
        }
        Thread thread = new Handler(UNIQUE_THREAD_ID.incrementAndGet());
        thread.setUncaughtExceptionHandler(new DefaultUncaughtExceptionHandler(LOGGER));
        thread.start();
    }

    void arclight$preLogin() throws Exception {
        String playerName = gameProfile.getName();
        InetAddress address = ((InetSocketAddress) connection.getRemoteAddress()).getAddress();
        UUID uniqueId = gameProfile.getId();
        CraftServer craftServer = (CraftServer) Bukkit.getServer();
        AsyncPlayerPreLoginEvent asyncEvent = new AsyncPlayerPreLoginEvent(playerName, address, uniqueId);
        craftServer.getPluginManager().callEvent(asyncEvent);
        if (PlayerPreLoginEvent.getHandlerList().getRegisteredListeners().length != 0) {
            PlayerPreLoginEvent event = new PlayerPreLoginEvent(playerName, address, uniqueId);
            if (asyncEvent.getResult() != PlayerPreLoginEvent.Result.ALLOWED) {
                event.disallow(asyncEvent.getResult(), asyncEvent.getKickMessage());
            }
            class SyncPreLogin extends Waitable<PlayerPreLoginEvent.Result> {

                @Override
                protected PlayerPreLoginEvent.Result evaluate() {
                    craftServer.getPluginManager().callEvent(event);
                    return event.getResult();
                }
            }
            Waitable<PlayerPreLoginEvent.Result> waitable = new SyncPreLogin();
            ((MinecraftServerBridge) server).bridge$queuedProcess(waitable);
            if (waitable.get() != PlayerPreLoginEvent.Result.ALLOWED) {
                disconnect(event.getKickMessage());
                return;
            }
        } else if (asyncEvent.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            disconnect(asyncEvent.getKickMessage());
            return;
        }
        LOGGER.info("UUID of player {} is {}", gameProfile.getName(), gameProfile.getId());
        state = ServerLoginPacketListenerImpl.State.NEGOTIATING;
    }

    /**
     * Handle custom query packets for Velocity Modern Forwarding
     * Following Mohist's implementation approach
     */
    @Inject(method = "handleCustomQueryPacket", at = @At("HEAD"), cancellable = true)
    private void luminara$handleVelocityForwarding(net.minecraft.network.protocol.login.ServerboundCustomQueryPacket packet, CallbackInfo ci) {
        VelocityManager velocityManager = VelocityManager.getInstance();

        if (!velocityManager.isVelocityForwardingEnabled()) {
            return; // Let normal processing continue
        }

        try {
            // Get transaction ID and data using reflection (like Mohist does)
            int transactionId = luminara$getTransactionId(packet);
            net.minecraft.network.FriendlyByteBuf buf = luminara$getPacketData(packet);

            LOGGER.debug("Received custom query packet - ID: {}, Expected: {}, HasData: {}",
                    transactionId, this.luminara$velocityLoginMessageId, buf != null);

            if (transactionId == this.luminara$velocityLoginMessageId) {
                if (buf == null) {
                    LOGGER.warn("Received Velocity query response with null data");
                    this.disconnect("This server requires you to connect with Velocity.");
                    ci.cancel();
                    return;
                }

                LOGGER.debug("Processing Velocity forwarding data, buffer size: {}", buf.readableBytes());
                this.gameProfile = velocityManager.getVelocityForwarding().handleForwardingPacket(buf, this.connection);
                LOGGER.info("Successfully processed Velocity forwarding for player: {}", this.gameProfile.getName());

                // Handle online-mode logic
                if (velocityManager.getVelocityConfig().isOnlineMode()) {
                    LOGGER.debug("Online-mode enabled, proceeding with Mojang authentication for: {}", this.gameProfile.getName());
                    // Continue with normal authentication process
                    this.luminara$continueLogin();
                } else {
                    LOGGER.debug("Online-mode disabled, skipping Mojang authentication for: {}", this.gameProfile.getName());
                    // Skip Mojang authentication and proceed directly to login
                    this.luminara$proceedWithVelocityLogin();
                }
                ci.cancel();
            }
        } catch (Exception e) {
            LOGGER.warn("Exception processing Velocity forwarding packet from {}",
                    this.connection.getRemoteAddress(), e);
            this.disconnect("Unable to verify player details");
            ci.cancel();
        }
    }

    /**
     * Continue the login process after Velocity forwarding (with Mojang authentication)
     */
    private void luminara$continueLogin() {
        // Execute the login process in a separate thread, similar to Mohist's approach
        Thread thread = new Thread("Luminara Velocity Login") {
            @Override
            public void run() {
                try {
                    arclight$preLogin();
                } catch (Exception ex) {
                    disconnect("Failed to verify username!");
                    LOGGER.warn("Exception verifying " + gameProfile.getName(), ex);
                }
            }
        };
        thread.setUncaughtExceptionHandler(new DefaultUncaughtExceptionHandler(LOGGER));
        thread.start();
    }

    /**
     * Proceed with Velocity login without Mojang authentication (online-mode=false)
     */
    private void luminara$proceedWithVelocityLogin() {
        // Execute the login process in a separate thread, but skip Mojang authentication
        Thread thread = new Thread("Luminara Velocity Direct Login") {
            @Override
            public void run() {
                try {
                    // Skip Mojang authentication and proceed directly
                    LOGGER.info("UUID of player {} is {} (from Velocity)", gameProfile.getName(), gameProfile.getId());
                    state = ServerLoginPacketListenerImpl.State.NEGOTIATING; // FORGE: continue NEGOTIATING
                } catch (Exception ex) {
                    disconnect("Failed to process Velocity login!");
                    LOGGER.warn("Exception processing Velocity login for " + gameProfile.getName(), ex);
                }
            }
        };
        thread.setUncaughtExceptionHandler(new DefaultUncaughtExceptionHandler(LOGGER));
        thread.start();
    }

    /**
     * Get transaction ID from custom query packet using Mohist's exact method names
     */
    private int luminara$getTransactionId(net.minecraft.network.protocol.login.ServerboundCustomQueryPacket packet) throws Exception {
        // Use the exact obfuscated method name from Mohist patch: m_179824_()
        var method = packet.getClass().getMethod("m_179824_");
        return (Integer) method.invoke(packet);
    }

    /**
     * Get data from custom query packet using Mohist's exact method names
     */
    private net.minecraft.network.FriendlyByteBuf luminara$getPacketData(net.minecraft.network.protocol.login.ServerboundCustomQueryPacket packet) throws Exception {
        // Use the exact obfuscated method name from Mohist patch: m_179825_()
        var method = packet.getClass().getMethod("m_179825_");
        return (net.minecraft.network.FriendlyByteBuf) method.invoke(packet);
    }
}
