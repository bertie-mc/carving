package com.berlord.carving.block;

import com.berlord.carving.Carving;
import com.berlord.carving.CarvingMaterial;
import com.berlord.carving.item.SlateItem;
import com.berlord.carving.menu.CarvingStationMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Containers;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.items.ItemStackHandler;
import org.jetbrains.annotations.Nullable;

/** Holds the station's input (diamond slate) and output (carved head) stacks. */
public class CarvingStationBlockEntity extends BlockEntity implements MenuProvider {
    public static final int SLOT_INPUT = 0;
    public static final int SLOT_OUTPUT = 1;

    public final ItemStackHandler inv = new ItemStackHandler(2) {
        @Override
        protected void onContentsChanged(int slot) {
            setChanged();
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            if (slot == SLOT_INPUT) {
                return stack.getItem() instanceof SlateItem si && si.material.isStationOnly();
            }
            return false; // output is populated only by the carve result, never by the player
        }
    };

    public CarvingStationBlockEntity(BlockPos pos, BlockState state) {
        super(Carving.CARVING_STATION_BE.get(), pos, state);
    }

    public void dropContents(Level level, BlockPos pos) {
        for (int i = 0; i < inv.getSlots(); i++) {
            Containers.dropItemStack(level, pos.getX(), pos.getY(), pos.getZ(), inv.getStackInSlot(i));
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.put("inv", inv.serializeNBT(registries));
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("inv")) {
            inv.deserializeNBT(registries, tag.getCompound("inv"));
        }
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.berlords_carving.carving_station");
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory playerInventory, Player player) {
        return new CarvingStationMenu(containerId, playerInventory, this);
    }
}
