package io.izzel.arclight.common.mod.plugin.messaging;

import com.google.common.base.Preconditions;
import io.netty.buffer.ByteBuf;
import io.netty.util.ReferenceCountUtil;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.common.custom.DiscardedPayload;
import net.minecraft.resources.ResourceLocation;

/** Read from channel: native/heap retained buffer; */
public interface RawPayload extends CustomPacketPayload {
    /** Get the underlying buffer. Only used by codecs. */
    ByteBuf arclight$getRawData();

    /** Set the underlying buffer. Only used by codecs. */
    void arclight$setData(ByteBuf data);

    /** Unbox the payload and retrieve all readable bytes. */
    default byte[] arclight$leak() {
        final var buf = arclight$getRawData();
        byte[] allocate = new byte[buf.readableBytes()];
        buf.readBytes(allocate);
        ReferenceCountUtil.release(buf);
        arclight$setData(null);
        return allocate;
    }

    /**
     * Get an unretained slice of the underlying buffer.
     * @return a slice of the underlying buffer.
     */
    default ByteBuf arclight$getSlicedData() {
        // NeoForge will attempt to split packets to avoid massive packets.
        return arclight$getRawData().slice();
    }

    static <B extends FriendlyByteBuf> StreamCodec<B, ArclightRawPayload> channelCodec(CustomPacketPayload.Type<ArclightRawPayload> type, int max) {
        return StreamCodec.composite(
                StreamCodec.of(FriendlyByteBuf::writeBytes, buf -> {
                    var size = buf.readableBytes();
                    Preconditions.checkArgument(size <= max, "Custom payload size may not be larger than " + max);
                    return buf.readRetainedSlice(size);
                }),
                RawPayload::arclight$getSlicedData,
                it -> new ArclightRawPayload(type, it)
        );
    }

    static <B extends FriendlyByteBuf> StreamCodec<B, CustomPacketPayload> discardedCodec(ResourceLocation location, int max) {
        return new StreamCodec<>() {
            @Override
            public DiscardedPayload decode(B buf) {
                int j = buf.readableBytes();
                if (j >= 0 && j <= max) {
                    var data = buf.readRetainedSlice(j);
                    var payload = new DiscardedPayload(location);
                    ((RawPayload)(Object) payload).arclight$setData(data);
                    return payload;
                } else {
                    throw new IllegalArgumentException("Payload may not be larger than " + max + " bytes");
                }
            }

            @Override
            public void encode(B buf, CustomPacketPayload obj) {
                if (obj instanceof RawPayload raw) {
                    buf.writeBytes(raw.arclight$getSlicedData());
                }
            }
        };
    }

}
