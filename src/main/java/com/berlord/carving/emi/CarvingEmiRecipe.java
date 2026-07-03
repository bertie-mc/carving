package com.berlord.carving.emi;

import dev.emi.emi.api.recipe.BasicEmiRecipe;
import dev.emi.emi.api.recipe.EmiRecipeCategory;
import dev.emi.emi.api.render.EmiTexture;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.api.widget.WidgetHolder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
 * One synthesized carving "recipe": a material slate becomes a tool head or armor part. Carving has
 * no {@code RecipeType} (it's a GUI/minigame), so these are built in code from the slate item and
 * {@code Carving.resultStack(...)} rather than read from the recipe manager. Layout: slate -> arrow
 * -> result, with a line saying whether the material is carved in hand (tier 1) or at the station (tier 2).
 */
public class CarvingEmiRecipe extends BasicEmiRecipe {
    private static final int PAD = 2;
    private static final int SLOT = 18;
    private static final int ARROW_W = 24;
    private static final int ARROW_H = 17;

    private final EmiIngredient input;
    private final EmiStack output;
    private final Component tierLine;

    public CarvingEmiRecipe(EmiRecipeCategory category, ResourceLocation id,
                            EmiIngredient input, EmiStack output, Component tierLine) {
        super(category, id, PAD + SLOT + PAD + ARROW_W + PAD + SLOT + PAD, PAD + SLOT + PAD + 10 + PAD);
        this.input = input;
        this.output = output;
        this.tierLine = tierLine;
        this.inputs = List.of(input);
        this.outputs = List.of(output);
        this.catalysts = List.of();
    }

    @Override
    public void addWidgets(WidgetHolder w) {
        int arrowX = PAD + SLOT + PAD;
        int outX = arrowX + ARROW_W + PAD;
        w.addSlot(input, PAD, PAD).recipeContext(this);
        w.addTexture(EmiTexture.EMPTY_ARROW, arrowX, PAD + (SLOT - ARROW_H) / 2);
        w.addSlot(output, outX, PAD).recipeContext(this);
        w.addText(tierLine, PAD, PAD + SLOT + PAD, 0xFF404040, false);
    }
}
