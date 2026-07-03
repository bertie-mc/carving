package com.berlord.carving.compat;

import com.berlord.carving.Carving;
import com.mojang.datafixers.util.Pair;
import dev.lopyluna.slag.content.items.dynamic_part.IModularItem;
import dev.lopyluna.slag.register.AllDataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Unit;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Optional Slag-n-Embers integration. References Slag classes, so it is ONLY ever loaded/called from a
 * path guarded by {@code ModList.isLoaded("slag")} — when Slag is absent this class never loads.
 *
 * After Slag assembles a tool, the carved part (carrying our {@code berlords_carving:flaws}) is stored
 * inside the tool's parts. We read that, apply a one-time CURRENT-durability penalty (25% per flaw,
 * repairable), and mark the tool so it never re-applies.
 */
public final class SlagCompat {
    private SlagCompat() {
    }

    /**
     * Build a {@code slag:dynamic_part} ItemStack carrying material_type/part_type. This is exactly what
     * Slag's own DynamicPartItem.setMaterialType/setPartType do (the raw {@code slag:<path>}
     * ResourceLocation IS the component value). Only ever called when {@code ModList.isLoaded("slag")}.
     *
     * @param materialPath Slag material path, e.g. "iron" (NOTE: wood -> "wooden", gold -> "golden")
     * @param partPath     Slag part path, e.g. "pickaxe_head" / "helmet"
     */
    public static ItemStack buildDynamicPart(String materialPath, String partPath) {
        Item item = BuiltInRegistries.ITEM.get(ResourceLocation.fromNamespaceAndPath("slag", "dynamic_part"));
        if (item == Items.AIR) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = new ItemStack(item);
        stack.set(AllDataComponents.MATERIAL_TYPE.get(), ResourceLocation.fromNamespaceAndPath("slag", materialPath));
        stack.set(AllDataComponents.PART_TYPE.get(), ResourceLocation.fromNamespaceAndPath("slag", partPath));
        return stack;
    }

    public static void scanAndPenalize(Player player) {
        var inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()
                    || !(stack.getItem() instanceof IModularItem modular)
                    || stack.has(Carving.PENALIZED.get())) {
                continue;
            }
            int maxFlaws = 0;
            int maxPenalty = 0;
            int partCount = 0;
            try {
                for (Pair<ItemStack, ?> part : modular.getDynamicParts(stack)) {
                    partCount++;
                    maxFlaws = Math.max(maxFlaws, part.getFirst().getOrDefault(Carving.FLAWS.get(), 0));
                    maxPenalty = Math.max(maxPenalty, part.getFirst().getOrDefault(Carving.PENALTY.get(), 0));
                }
            } catch (Throwable ignored) {
                continue; // be defensive against Slag internal changes - never crash the tick
            }
            // tier 1 parts carry flaws (-25% each), tier 2 parts carry penalty steps (-30% each)
            float frac = maxPenalty > 0 ? maxPenalty * 0.30F : maxFlaws * 0.25F;
            if (frac <= 0) {
                continue; // not a flawed-carve tool (don't mark - it may not be assembled yet)
            }
            // Slag computes getMaxDamage() = round(getDura) ONLY when the MODULAR_TYPE component is
            // present (the item is fully assembled). Before that it falls back to the tiny base-item
            // durability, so applying the penalty now (round(base * frac)) would near-destroy the item
            // and leave ~1 durability. Wait for a later tick when the modular type is set; until then
            // do NOT mark it penalized so we retry.
            boolean hasModType = stack.has(AllDataComponents.MODULAR_TYPE.get());
            int max = stack.getMaxDamage();
            // === TEMP DIAGNOSTIC (durability bug) ===
            Carving.LOGGER.info(
                    "[carve-dbg] item={} hasModularType={} hasDynParts={} parts={} maxPenalty={} maxFlaws={} frac={} getMaxDamage={} curDamage={} computedDmg={}",
                    stack.getHoverName().getString(), hasModType,
                    stack.has(AllDataComponents.DYNAMIC_PARTS.get()), partCount, maxPenalty,
                    maxFlaws, frac, max, stack.getDamageValue(), Math.round(max * frac));
            // === END TEMP DIAGNOSTIC ===
            if (!hasModType || max <= 1) {
                continue; // not fully assembled yet (or unbreakable) - retry on a later tick
            }
            int dmg = Math.min(max - 1, Math.round(max * frac));
            stack.setDamageValue(Math.max(stack.getDamageValue(), dmg));
            stack.set(Carving.PENALIZED.get(), Unit.INSTANCE);
        }
    }
}
