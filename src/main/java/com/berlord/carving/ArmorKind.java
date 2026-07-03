package com.berlord.carving;

/** The four armor pieces a big slate can be carved into. Ordinal is the network index. */
public enum ArmorKind {
    HELMET("helmet"),
    CHESTPLATE("chestplate"),
    LEGGINGS("leggings"),
    BOOTS("boots");

    public final String id;

    ArmorKind(String id) {
        this.id = id;
    }

    public static ArmorKind byIndex(int i) {
        ArmorKind[] v = values();
        return (i >= 0 && i < v.length) ? v[i] : HELMET;
    }
}
