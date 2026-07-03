package com.berlord.carving.net;

import com.berlord.carving.Carving;
import com.berlord.carving.CarvingMaterial;
import com.berlord.carving.item.SlateItem;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client -> server: the outcome of an in-hand (tier 1) carving session.
 * errors 0-2 = success, part produced with that many flaws; errors >= 3 = the slate was ruined.
 * {@code armor} selects whether {@code kind} is an {@link ArmorKind} (big slate) or {@link ToolKind}.
 */
public record CarveResultPayload(int material, boolean armor, int kind, int errors, boolean mainHand)
        implements CustomPacketPayload {

    public static final Type<CarveResultPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Carving.MODID, "carve_result"));

    public static final StreamCodec<RegistryFriendlyByteBuf, CarveResultPayload> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> {
                buf.writeVarInt(p.material());
                buf.writeBoolean(p.armor());
                buf.writeVarInt(p.kind());
                buf.writeVarInt(p.errors());
                buf.writeBoolean(p.mainHand());
            },
            buf -> new CarveResultPayload(buf.readVarInt(), buf.readBoolean(), buf.readVarInt(),
                    buf.readVarInt(), buf.readBoolean()));

    @Override
    public Type<CarveResultPayload> type() {
        return TYPE;
    }

    public static void handle(CarveResultPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            Player player = context.player();
            InteractionHand hand = payload.mainHand() ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND;
            ItemStack held = player.getItemInHand(hand);

            // Server-side validation: the player must still hold the claimed slate (material + size).
            if (!(held.getItem() instanceof SlateItem slate)) {
                return;
            }
            CarvingMaterial mat = CarvingMaterial.byIndex(payload.material());
            if (slate.material != mat || slate.big != payload.armor()) {
                return;
            }

            int errors = Mth.clamp(payload.errors(), 0, 3);
            held.shrink(1);

            if (errors >= 3) {
                player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
                        SoundEvents.ITEM_BREAK, SoundSource.PLAYERS, 0.8F, 0.9F);
                return;
            }

            // direct result: slag part (Slag present) or full vanilla tool/armor; tier-1 errors = flaws
            ItemStack part = Carving.resultStack(mat, payload.armor(), payload.kind(), errors, 0);
            if (part.isEmpty()) {
                return;
            }
            player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.AXE_STRIP, SoundSource.PLAYERS, 0.7F, 1.0F);
            if (!player.getInventory().add(part)) {
                player.drop(part, false);
            }
        });
    }
}
