package com.berlord.carving.emi;

import dev.emi.emi.api.recipe.EmiRecipeCategory;
import dev.emi.emi.api.render.EmiRenderable;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/** The single "Carving" EMI category. Name is explicit (we don't rely on EMI's translation-key convention). */
public class CarvingEmiCategory extends EmiRecipeCategory {
    private final Component name;

    public CarvingEmiCategory(ResourceLocation id, EmiRenderable icon, Component name) {
        super(id, icon);
        this.name = name;
    }

    @Override
    public Component getName() {
        return name;
    }
}
