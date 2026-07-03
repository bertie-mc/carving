package com.berlord.carving.net;

import com.berlord.carving.Carving;
import com.berlord.carving.CarvingMaterial;
import com.berlord.carving.block.CarvingStationBlockEntity;
import com.berlord.carving.item.SlateItem;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client -> server: outcome of a tier-2 water-jet carve at {@code pos}.
 * {@code success} = part produced with {@code errors} carving errors; otherwise the slate is consumed
 * with no output (3rd error = break, or Esc-abandon). Error curve: 1 free, 2 = -30% durability,
 * 3 = break. The penalty (0-1 steps, -30%) rides on the carved part via {@link Carving#PENALTY}.
 */
public record StationCarveResultPayload(BlockPos pos, int material, boolean armor, int kind,
                                        int errors, boolean success) implements CustomPacketPayload {

    public static final Type<StationCarveResultPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Carving.MODID, "station_carve_result"));

    public static final StreamCodec<RegistryFriendlyByteBuf, StationCarveResultPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, p) -> {
                        buf.writeBlockPos(p.pos());
                        buf.writeVarInt(p.material());
                        buf.writeBoolean(p.armor());
                        buf.writeVarInt(p.kind());
                        buf.writeVarInt(p.errors());
                        buf.writeBoolean(p.success());
                    },
                    buf -> new StationCarveResultPayload(buf.readBlockPos(), buf.readVarInt(),
                            buf.readBoolean(), buf.readVarInt(), buf.readVarInt(), buf.readBoolean()));

    @Override
    public Type<StationCarveResultPayload> type() {
        return TYPE;
    }

    public static void handle(StationCarveResultPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            if (!(player.level().getBlockEntity(payload.pos()) instanceof CarvingStationBlockEntity be)) {
                return;
            }
            if (player.distanceToSqr(payload.pos().getX() + 0.5, payload.pos().getY() + 0.5,
                    payload.pos().getZ() + 0.5) >= 64.0) {
                return;
            }
            ItemStack input = be.inv.getStackInSlot(CarvingStationBlockEntity.SLOT_INPUT);
            CarvingMaterial mat = CarvingMaterial.byIndex(payload.material());
            // Validate the input is genuinely the claimed tier-2 slate (anti-spoof).
            if (!(input.getItem() instanceof SlateItem slate)
                    || slate.material != mat || !slate.material.isStationOnly()
                    || slate.big != payload.armor()) {
                return;
            }
            if (!be.inv.getStackInSlot(CarvingStationBlockEntity.SLOT_OUTPUT).isEmpty()) {
                return; // output occupied; client shouldn't have let carving start
            }

            // the water-jet only carves while the station is waterlogged (matches the client gate;
            // also stops a spoofed success from producing an item with the water drained)
            var bs = player.level().getBlockState(payload.pos());
            boolean waterlogged = bs.getBlock() instanceof com.berlord.carving.block.CarvingStationBlock
                    && bs.getValue(com.berlord.carving.block.CarvingStationBlock.WATERLOGGED);

            be.inv.extractItem(CarvingStationBlockEntity.SLOT_INPUT, 1, false);

            if (!payload.success() || !waterlogged) {
                player.level().playSound(null, payload.pos().getX() + 0.5, payload.pos().getY() + 0.5,
                        payload.pos().getZ() + 0.5, SoundEvents.ITEM_BREAK, SoundSource.BLOCKS, 0.8F, 0.9F);
                return;
            }

            int penalty = Mth.clamp(payload.errors() - 1, 0, 1); // 1 free, 2 = -30%, 3 = break
            // direct result: slag part (Slag present) or full vanilla tool/armor, penalty ridden on
            ItemStack part = Carving.resultStack(mat, payload.armor(), payload.kind(), 0, penalty);
            if (part.isEmpty()) {
                return;
            }
            be.inv.setStackInSlot(CarvingStationBlockEntity.SLOT_OUTPUT, part);
            player.level().playSound(null, payload.pos().getX() + 0.5, payload.pos().getY() + 0.5,
                    payload.pos().getZ() + 0.5, SoundEvents.GRINDSTONE_USE, SoundSource.BLOCKS, 0.7F, 1.2F);
        });
    }
}
