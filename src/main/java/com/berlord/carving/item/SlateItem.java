package com.berlord.carving.item;

import com.berlord.carving.CarvingMaterial;
import com.berlord.carving.net.OpenCarvingPayload;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;

/**
 * A blank "slate" of a given material. A small slate carves tool heads; a big slate carves armor
 * pieces. Tier 1 slates open the in-hand carving screen; tier 2 slates are worked at the station.
 */
public class SlateItem extends Item {
    public final CarvingMaterial material;
    /** false = small (tools), true = big (armor). */
    public final boolean big;

    public SlateItem(Properties properties, CarvingMaterial material, boolean big) {
        super(properties);
        this.material = material;
        this.big = big;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (material.isStationOnly()) {
            return InteractionResultHolder.pass(stack); // tier 2 slates are worked at the carving station
        }
        if (!level.isClientSide && player instanceof ServerPlayer sp) {
            PacketDistributor.sendToPlayer(sp,
                    new OpenCarvingPayload(material.ordinal(), big, hand == InteractionHand.MAIN_HAND));
        }
        return InteractionResultHolder.success(stack);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("berlords_carving.tier." + material.tier)
                .withStyle(ChatFormatting.GRAY));
        super.appendHoverText(stack, context, tooltip, flag);
    }
}
