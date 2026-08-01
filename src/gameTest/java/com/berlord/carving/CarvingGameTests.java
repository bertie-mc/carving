package com.berlord.carving;

import com.berlord.carving.block.CarvingStationBlock;
import com.berlord.carving.block.CarvingStationBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("minecraft")
@PrefixGameTestTemplate(false)
public final class CarvingGameTests {
    private static final String EMPTY_TEMPLATE = "bastion/mobs/empty";

    private CarvingGameTests() {
    }

    @GameTest(template = EMPTY_TEMPLATE)
    public static void waterloggedStationAcceptsOnlyTierTwoSlates(GameTestHelper helper) {
        BlockPos relativePos = new BlockPos(1, 1, 1);
        BlockPos worldPos = helper.absolutePos(relativePos);
        CarvingStationBlock station = Carving.CARVING_STATION.get();
        BlockState dry = station.defaultBlockState();
        helper.getLevel().setBlock(worldPos, dry, 3);

        helper.assertTrue(station.placeLiquid(helper.getLevel(), worldPos, dry,
                Fluids.WATER.getSource(false)), "station should accept water");
        BlockState waterlogged = helper.getLevel().getBlockState(worldPos);
        helper.assertTrue(waterlogged.getValue(CarvingStationBlock.WATERLOGGED),
                "station should retain the waterlogged state");
        helper.assertTrue(waterlogged.getFluidState().is(Fluids.WATER),
                "waterlogged station should expose a water fluid state");

        var blockEntity = helper.getLevel().getBlockEntity(worldPos);
        helper.assertTrue(blockEntity instanceof CarvingStationBlockEntity,
                "station block entity is missing");
        CarvingStationBlockEntity stationEntity = (CarvingStationBlockEntity) blockEntity;
        ItemStack diamondSlate = new ItemStack(Carving.SMALL_SLATES.get(CarvingMaterial.DIAMOND).get());
        ItemStack woodSlate = new ItemStack(Carving.SMALL_SLATES.get(CarvingMaterial.WOOD).get());
        helper.assertTrue(stationEntity.inv.isItemValid(CarvingStationBlockEntity.SLOT_INPUT, diamondSlate),
                "tier-two slate should be accepted");
        helper.assertFalse(stationEntity.inv.isItemValid(CarvingStationBlockEntity.SLOT_INPUT, woodSlate),
                "tier-one slate should be rejected");
        helper.assertFalse(stationEntity.inv.isItemValid(CarvingStationBlockEntity.SLOT_OUTPUT, diamondSlate),
                "players must not insert into the output slot");
        helper.succeed();
    }
}
