package com.berlord.carving.emi;

import com.berlord.carving.ArmorKind;
import com.berlord.carving.Carving;
import com.berlord.carving.CarvingMaterial;
import com.berlord.carving.ToolKind;
import dev.emi.emi.api.EmiEntrypoint;
import dev.emi.emi.api.EmiPlugin;
import dev.emi.emi.api.EmiRegistry;
import dev.emi.emi.api.stack.EmiStack;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * Built-in EMI plugin for Berlord's Carving. EMI ASM-discovers this via {@link EmiEntrypoint}
 * (RuntimeInvisible — never load this class server-side, where EMI is absent). It synthesizes one
 * "Carving" category by enumerating every registered slate against every tool/armor kind and asking
 * {@link Carving#resultStack} what that carve produces (a Slag {@code dynamic_part} with Slag present,
 * else the vanilla tool/armor item) — there is no recipe type to read.
 */
@EmiEntrypoint
public class CarvingEmiPlugin implements EmiPlugin {
    private static final ResourceLocation CATEGORY_ID =
            ResourceLocation.fromNamespaceAndPath(Carving.MODID, "carving");

    @Override
    public void register(EmiRegistry registry) {
        EmiStack station = EmiStack.of(Carving.CARVING_STATION_ITEM.get());
        CarvingEmiCategory category = new CarvingEmiCategory(CATEGORY_ID, station,
                Component.translatable("emi.category.berlords_carving.carving"));
        registry.addCategory(category);
        registry.addWorkstation(category, station);

        for (CarvingMaterial m : CarvingMaterial.values()) {
            Component tier = Component.translatable("berlords_carving.emi.tier" + m.tier);
            if (Carving.SMALL_SLATES.containsKey(m)) {
                EmiStack slate = EmiStack.of(Carving.SMALL_SLATES.get(m).get());
                for (ToolKind k : ToolKind.values()) {
                    addRecipe(registry, category, m, slate, false, k.ordinal(), k.id, tier);
                }
            }
            if (Carving.BIG_SLATES.containsKey(m)) {
                EmiStack slate = EmiStack.of(Carving.BIG_SLATES.get(m).get());
                for (ArmorKind k : ArmorKind.values()) {
                    addRecipe(registry, category, m, slate, true, k.ordinal(), k.id, tier);
                }
            }
        }
    }

    private static void addRecipe(EmiRegistry registry, CarvingEmiCategory category, CarvingMaterial m,
                                  EmiStack slate, boolean armor, int kindIndex, String kindId, Component tier) {
        ItemStack result = Carving.resultStack(m, armor, kindIndex, 0, 0);
        if (result.isEmpty()) {
            return; // e.g. a Slag-only material with Slag absent
        }
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(Carving.MODID,
                "carving/" + (armor ? "armor/" : "tool/") + m.id + "_" + kindId);
        registry.addRecipe(new CarvingEmiRecipe(category, id, slate, EmiStack.of(result), tier));
    }
}
