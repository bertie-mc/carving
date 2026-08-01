package com.berlord.carving;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

class CarvingContractTest {

    @Test
    void networkOrdinalsRemainStableAndInvalidIndicesFallBack() {
        assertEquals(List.of(
                "wood", "stone", "flint", "bone", "diamond", "leather", "copper", "iron",
                "golden", "emerald", "amethyst", "lapis", "quartz", "obsidian", "echo",
                "deep_alloy", "rose_gold"),
                Arrays.stream(CarvingMaterial.values()).map(material -> material.id).toList());
        assertSame(CarvingMaterial.WOOD, CarvingMaterial.byIndex(-1));
        assertSame(CarvingMaterial.WOOD, CarvingMaterial.byIndex(100));
        assertSame(ToolKind.PICKAXE, ToolKind.byIndex(-1));
        assertSame(ArmorKind.HELMET, ArmorKind.byIndex(100));
    }

    @Test
    void standaloneRegistrationExposesOnlyUsableSlates() {
        assertEquals(Set.of(CarvingMaterial.WOOD, CarvingMaterial.STONE, CarvingMaterial.DIAMOND,
                CarvingMaterial.IRON, CarvingMaterial.GOLDEN), Carving.SMALL_SLATES.keySet());
        assertEquals(Set.of(CarvingMaterial.DIAMOND, CarvingMaterial.LEATHER,
                CarvingMaterial.IRON, CarvingMaterial.GOLDEN), Carving.BIG_SLATES.keySet());
        assertFalse(Carving.usesSlag(CarvingMaterial.IRON));
    }

    @Test
    void vanillaResultsUseTheRequestedKindAndDurabilityPenalty() {
        ItemStack axe = Carving.resultStack(CarvingMaterial.WOOD, false,
                ToolKind.AXE.ordinal(), 2, 0);
        assertSame(Items.WOODEN_AXE, axe.getItem());
        assertEquals(Math.round(axe.getMaxDamage() * 0.50F), axe.getDamageValue());

        ItemStack chestplate = Carving.resultStack(CarvingMaterial.DIAMOND, true,
                ArmorKind.CHESTPLATE.ordinal(), 0, 1);
        assertSame(Items.DIAMOND_CHESTPLATE, chestplate.getItem());
        assertEquals(Math.round(chestplate.getMaxDamage() * 0.30F), chestplate.getDamageValue());
    }

    @Test
    void shapeKeysAlwaysSelectPartSilhouettes() {
        assertEquals("slag/pickaxe_head", Carving.shapeKey(
                CarvingMaterial.WOOD, false, ToolKind.PICKAXE.ordinal()));
        assertEquals("slag/boots", Carving.shapeKey(
                CarvingMaterial.DIAMOND, true, ArmorKind.BOOTS.ordinal()));
    }
}
