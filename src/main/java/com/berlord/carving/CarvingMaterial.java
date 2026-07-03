package com.berlord.carving;

/**
 * A material a slate can be made of. Ordinal is the network index, so existing entries keep their
 * position (WOOD..DIAMOND unchanged); new materials are appended.
 *
 * <p>TIER 1 materials are carved in-hand ({@link com.berlord.carving.client.CarvingScreen}); TIER 2
 * materials are worked only at the carving station's water-jet ({@link
 * com.berlord.carving.block.CarvingStationBlock}). Every material has armor; only {@link #LEATHER}
 * lacks tools. {@code slagId == null} means the material has no Slag equivalent (leather is always
 * vanilla). {@code vanillaTool}/{@code vanillaArmor} name the vanilla item prefix used when Slag is
 * absent (null = that form is Slag-only and is hidden without Slag).
 */
public enum CarvingMaterial {
    WOOD("wood", "wooden", 1, true, "wooden", null),
    STONE("stone", "stone", 1, true, "stone", null),
    FLINT("flint", "flint", 1, true, null, null),
    BONE("bone", "bone", 1, true, null, null),
    DIAMOND("diamond", "diamond", 2, true, "diamond", "diamond"),
    LEATHER("leather", null, 1, false, null, "leather"),
    COPPER("copper", "copper", 2, true, null, null),
    IRON("iron", "iron", 2, true, "iron", "iron"),
    GOLDEN("golden", "golden", 2, true, "golden", "golden"),
    EMERALD("emerald", "emerald", 2, true, null, null),
    AMETHYST("amethyst", "amethyst", 2, true, null, null),
    LAPIS("lapis", "lapis", 2, true, null, null),
    QUARTZ("quartz", "quartz", 2, true, null, null),
    OBSIDIAN("obsidian", "obsidian", 2, true, null, null),
    ECHO("echo", "echo", 2, true, null, null),
    DEEP_ALLOY("deep_alloy", "deep_alloy", 2, true, null, null),
    ROSE_GOLD("rose_gold", "rose_gold", 2, true, null, null);

    public final String id;
    /** Slag material_type id (without the slag: namespace), or null if there's no Slag equivalent. */
    public final String slagId;
    public final int tier;
    public final boolean hasTools;
    public final String vanillaTool;
    public final String vanillaArmor;

    CarvingMaterial(String id, String slagId, int tier, boolean hasTools,
                    String vanillaTool, String vanillaArmor) {
        this.id = id;
        this.slagId = slagId;
        this.tier = tier;
        this.hasTools = hasTools;
        this.vanillaTool = vanillaTool;
        this.vanillaArmor = vanillaArmor;
    }

    /** Worked only at the carving station (water-jet), never the in-hand screen. */
    public boolean isStationOnly() {
        return tier == 2;
    }

    public static CarvingMaterial byIndex(int i) {
        CarvingMaterial[] v = values();
        return (i >= 0 && i < v.length) ? v[i] : WOOD;
    }
}
