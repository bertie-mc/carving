package com.berlord.carving;

/** The five tool heads that can be carved. Ordinal is the network index. */
public enum ToolKind {
    PICKAXE("pickaxe", "pickaxe_head"),
    AXE("axe", "axe_head"),
    SHOVEL("shovel", "shovel_head"),
    HOE("hoe", "hoe_head"),
    SWORD("sword", "sword_blade");

    /** vanilla item suffix (minecraft:&lt;mat&gt;_&lt;id&gt;) AND the vanilla-shape file name. */
    public final String id;
    /** Slag part_type path AND the slag-shape file name. */
    public final String slagPart;

    ToolKind(String id, String slagPart) {
        this.id = id;
        this.slagPart = slagPart;
    }

    public static ToolKind byIndex(int i) {
        ToolKind[] v = values();
        return (i >= 0 && i < v.length) ? v[i] : PICKAXE;
    }
}
