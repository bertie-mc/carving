package com.berlord.carving.menu;

import com.berlord.carving.Carving;
import com.berlord.carving.block.CarvingStationBlockEntity;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.items.SlotItemHandler;
import org.jetbrains.annotations.Nullable;

/**
 * Slotted workstation: input (diamond slate) + output (carved head), plus the player inventory.
 * The carving canvas itself is drawn/handled client-side in CarvingStationScreen; this menu only
 * owns the item slots and their server-authoritative state.
 */
public class CarvingStationMenu extends AbstractContainerMenu {
    // GUI-local layout, single source of truth shared with CarvingStationScreen.
    public static final int IMG_W = 220, IMG_H = 324;
    public static final int TABS_LEFT = 50, TABS_Y = 18, TAB_W = 24, TAB_H = 22;
    public static final int GRID_X = 26, GRID_Y = 46, CELL = 10, GRID_PX = 16 * CELL; // 160
    public static final int INV_X = 29, INV_Y = 242, HOTBAR_Y = 300;
    // function slots raised above the inventory, x-aligned to inventory columns 1 and 7 (anvil-style)
    public static final int INPUT_X = INV_X + 18, INPUT_Y = INV_Y - 22;       // 47, 220
    public static final int OUTPUT_X = INV_X + 7 * 18, OUTPUT_Y = INV_Y - 22; // 155, 220

    @Nullable
    public final CarvingStationBlockEntity be;

    public CarvingStationMenu(int containerId, Inventory playerInventory, RegistryFriendlyByteBuf buf) {
        this(containerId, playerInventory, asStation(playerInventory, buf));
    }

    public CarvingStationMenu(int containerId, Inventory playerInventory, @Nullable CarvingStationBlockEntity be) {
        super(Carving.CARVING_STATION_MENU.get(), containerId);
        this.be = be;
        if (be != null) {
            addSlot(new SlotItemHandler(be.inv, CarvingStationBlockEntity.SLOT_INPUT, INPUT_X, INPUT_Y));
            addSlot(new SlotItemHandler(be.inv, CarvingStationBlockEntity.SLOT_OUTPUT, OUTPUT_X, OUTPUT_Y) {
                @Override
                public boolean mayPlace(ItemStack stack) {
                    return false;
                }
            });
        }
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(playerInventory, col + row * 9 + 9, INV_X + col * 18, INV_Y + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(playerInventory, col, INV_X + col * 18, HOTBAR_Y));
        }
    }

    @Nullable
    private static CarvingStationBlockEntity asStation(Inventory playerInventory, RegistryFriendlyByteBuf buf) {
        BlockEntity blockEntity = playerInventory.player.level().getBlockEntity(buf.readBlockPos());
        return blockEntity instanceof CarvingStationBlockEntity station ? station : null;
    }

    /** Block whatever extra cross-slot juggling a screen might attempt while the canvas is in use. */
    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        if (be == null) {
            return ItemStack.EMPTY;
        }
        Slot slot = this.slots.get(index);
        if (!slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = slot.getItem();
        ItemStack copy = stack.copy();
        int stationSlots = 2;
        int invStart = stationSlots;
        int invEnd = this.slots.size();

        if (index < stationSlots) {
            // input/output -> player inventory
            if (!this.moveItemStackTo(stack, invStart, invEnd, true)) {
                return ItemStack.EMPTY;
            }
            slot.onQuickCraft(stack, copy);
        } else {
            // player inventory -> input slot (only diamond slates will be accepted by the slot)
            if (!this.moveItemStackTo(stack, CarvingStationBlockEntity.SLOT_INPUT,
                    CarvingStationBlockEntity.SLOT_INPUT + 1, false)) {
                return ItemStack.EMPTY;
            }
        }
        if (stack.isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        if (stack.getCount() == copy.getCount()) {
            return ItemStack.EMPTY;
        }
        slot.onTake(player, stack);
        return copy;
    }

    @Override
    public boolean stillValid(Player player) {
        if (be == null || be.getLevel() == null) {
            return false;
        }
        return player.distanceToSqr(be.getBlockPos().getX() + 0.5,
                be.getBlockPos().getY() + 0.5, be.getBlockPos().getZ() + 0.5) < 64.0;
    }
}
