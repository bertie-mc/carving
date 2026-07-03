package com.berlord.carving.net;

import com.berlord.carving.Carving;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Server -> client: open the in-hand carving screen for a slate of the given material (small/big). */
public record OpenCarvingPayload(int material, boolean armor, boolean mainHand) implements CustomPacketPayload {

    public static final Type<OpenCarvingPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Carving.MODID, "open_carving"));

    public static final StreamCodec<RegistryFriendlyByteBuf, OpenCarvingPayload> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> {
                buf.writeVarInt(p.material());
                buf.writeBoolean(p.armor());
                buf.writeBoolean(p.mainHand());
            },
            buf -> new OpenCarvingPayload(buf.readVarInt(), buf.readBoolean(), buf.readBoolean()));

    @Override
    public Type<OpenCarvingPayload> type() {
        return TYPE;
    }

    public static void handle(OpenCarvingPayload payload, IPayloadContext context) {
        // Runs only on the client (this is a play-to-client payload).
        context.enqueueWork(() -> com.berlord.carving.client.CarvingClient.openScreen(payload));
    }
}
