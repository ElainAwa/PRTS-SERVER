package io.izzel.arclight.common.mod.velocity;

import com.google.common.net.InetAddresses;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import io.izzel.arclight.common.bridge.core.network.NetworkManagerBridge;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Velocity Modern Forwarding implementation for Luminara
 * Based on Mohist's VelocityProxy implementation
 */
public class VelocityForwarding {

    public static final int MODERN_FORWARDING_WITH_KEY = 2;
    public static final int MODERN_FORWARDING_WITH_KEY_V2 = 3;
    public static final int MODERN_LAZY_SESSION = 4;
    public static final byte MAX_SUPPORTED_FORWARDING_VERSION = MODERN_LAZY_SESSION;
    public static final ResourceLocation PLAYER_INFO_CHANNEL = new ResourceLocation("velocity", "player_info");
    private static final Logger LOGGER = LogManager.getLogger("Luminara-Velocity");
    private static final int SUPPORTED_FORWARDING_VERSION = 1;
    private final String forwardingSecret;

    public VelocityForwarding(String forwardingSecret) {
        this.forwardingSecret = forwardingSecret;
        LOGGER.info("Velocity Modern Forwarding initialized");
    }

    @Nullable
    public GameProfile handleForwardingPacket(FriendlyByteBuf buf, Connection connection) throws Exception {
        if (buf == null) {
            throw new Exception("Got empty packet");
        }

        LOGGER.debug("Processing Velocity forwarding packet, readable bytes: {}", buf.readableBytes());

        if (!checkIntegrity(buf)) {
            throw new Exception("Player-data could not be validated!");
        }
        LOGGER.debug("Player-data validated!");

        int version = buf.readVarInt();
        if (version > MAX_SUPPORTED_FORWARDING_VERSION) {
            throw new IllegalStateException("Unsupported forwarding version " + version + ", wanted upto " + MAX_SUPPORTED_FORWARDING_VERSION);
        }
        LOGGER.debug("Velocity forwarding version: {}", version);

        // Get the current listening address for port information
        SocketAddress listening = connection.getRemoteAddress();
        int port = 0;
        if (listening instanceof InetSocketAddress) {
            port = ((InetSocketAddress) listening).getPort();
        }

        // Read the forwarded address
        InetAddress forwardedAddress = readAddress(buf);
        LOGGER.debug("Forwarded address: {}", forwardedAddress);

        // Set the forwarded address on the connection exactly like Mohist does
        try {
            // Use the obfuscated field name from Mohist: f_129469_
            var addressField = connection.getClass().getDeclaredField("f_129469_");
            addressField.setAccessible(true);
            addressField.set(connection, new InetSocketAddress(forwardedAddress, port));
            LOGGER.debug("Set forwarded address via f_129469_: {}:{}", forwardedAddress, port);
        } catch (Exception e) {
            LOGGER.warn("Failed to set forwarded address via f_129469_, trying alternatives", e);
            // Try other possible field names
            try {
                var addressField = connection.getClass().getDeclaredField("address");
                addressField.setAccessible(true);
                addressField.set(connection, new InetSocketAddress(forwardedAddress, port));
                LOGGER.debug("Set forwarded address via address field: {}:{}", forwardedAddress, port);
            } catch (Exception e2) {
                LOGGER.warn("Failed to set address via reflection, trying bridge method", e2);
                // Try the bridge method as last resort
                try {
                    ((NetworkManagerBridge) connection).bridge$setVelocityAddress(new InetSocketAddress(forwardedAddress, port));
                } catch (Exception e3) {
                    LOGGER.warn("Failed to set address via bridge", e3);
                }
            }
        }

        // Create the game profile
        GameProfile profile = createProfile(buf);
        LOGGER.debug("Created profile for player: {}", profile.getName());

        // Handle online-mode logic
        VelocityManager velocityManager = VelocityManager.getInstance();
        if (velocityManager.getVelocityConfig().isOnlineMode()) {
            LOGGER.debug("Online-mode is enabled, profile will be verified through Mojang servers");
        } else {
            LOGGER.debug("Online-mode is disabled, trusting forwarded profile from Velocity");
        }

        LOGGER.info("Successfully processed Velocity forwarding for player: {} (online-mode: {})",
                profile.getName(), velocityManager.getVelocityConfig().isOnlineMode());
        return profile;
    }

    public boolean checkIntegrity(final FriendlyByteBuf buf) {
        LOGGER.debug("Checking Velocity packet integrity, readable bytes: {}", buf.readableBytes());

        if (buf.readableBytes() < 33) { // 32 bytes signature + at least 1 byte data
            LOGGER.warn("Velocity packet too small: {} bytes", buf.readableBytes());
            return false;
        }

        final byte[] signature = new byte[32];
        buf.readBytes(signature);
        LOGGER.debug("Read signature: {} bytes", signature.length);

        final byte[] data = new byte[buf.readableBytes()];
        buf.getBytes(buf.readerIndex(), data);
        LOGGER.debug("Read data: {} bytes", data.length);

        try {
            final Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(forwardingSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            final byte[] mySignature = mac.doFinal(data);

            LOGGER.debug("Expected signature length: {}, actual signature length: {}", mySignature.length, signature.length);

            if (!MessageDigest.isEqual(signature, mySignature)) {
                LOGGER.warn("Velocity signature mismatch!");
                LOGGER.debug("Expected: {}", bytesToHex(mySignature));
                LOGGER.debug("Got: {}", bytesToHex(signature));
                LOGGER.debug("Secret: {}", forwardingSecret);
                LOGGER.debug("Data length: {}", data.length);
                return false;
            }

            LOGGER.debug("Velocity signature verification successful");
        } catch (final InvalidKeyException | NoSuchAlgorithmException e) {
            LOGGER.error("Error verifying Velocity signature", e);
            throw new AssertionError(e);
        }

        return true;
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte b : bytes) {
            result.append(String.format("%02x", b));
        }
        return result.toString();
    }

    public InetAddress readAddress(final FriendlyByteBuf buf) {
        return InetAddresses.forString(buf.readUtf(Short.MAX_VALUE));
    }

    public GameProfile createProfile(final FriendlyByteBuf buf) {
        final GameProfile profile = new GameProfile(buf.readUUID(), buf.readUtf(16));
        readProperties(buf, profile);
        return profile;
    }

    private void readProperties(final FriendlyByteBuf buf, final GameProfile profile) {
        final int properties = buf.readVarInt();
        for (int i1 = 0; i1 < properties; i1++) {
            final String name = buf.readUtf(Short.MAX_VALUE);
            final String value = buf.readUtf(Short.MAX_VALUE);
            final String signature = buf.readBoolean() ? buf.readUtf(Short.MAX_VALUE) : null;
            profile.getProperties().put(name, new Property(name, value, signature));
        }
    }
}
