package com.berlord.carving.client;

import com.berlord.carving.Carving;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.util.GsonHelper;
import org.slf4j.Logger;

import java.io.BufferedReader;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Loads the carving form maps from assets/berlords_carving/shapes/&lt;id&gt;.json (tool id or armor id).
 * Format: {@code {"pattern": [16 rows of 16 chars]}} where '.' = empty (carve away) and '0'-'7' = the
 * part's palette level at that cell. Edges between cells of differing level become the carving form-lines;
 * any non-'.' cell is part of the silhouette (keep).
 */
public final class ShapeLibrary {
    public static final int GRID = 16;
    public static final int CELLS = GRID * GRID;

    private static final Logger LOG = LogUtils.getLogger();
    private static final Map<String, int[]> CACHE = new HashMap<>();

    private ShapeLibrary() {
    }

    /** levels[i] = -1 for empty, else 0-7 palette level, for the shape file {@code shapes/<id>.json}. */
    public static int[] levels(String id) {
        return CACHE.computeIfAbsent(id, ShapeLibrary::load);
    }

    public static void clear() {
        CACHE.clear();
    }

    private static int[] load(String id) {
        ResourceLocation rl = ResourceLocation.fromNamespaceAndPath(Carving.MODID, "shapes/" + id + ".json");
        try {
            Optional<Resource> res = Minecraft.getInstance().getResourceManager().getResource(rl);
            if (res.isPresent()) {
                try (BufferedReader reader = res.get().openAsReader()) {
                    JsonObject json = GsonHelper.parse(reader);
                    JsonArray pattern = GsonHelper.getAsJsonArray(json, "pattern");
                    int[] lv = new int[CELLS];
                    java.util.Arrays.fill(lv, -1);
                    for (int row = 0; row < GRID && row < pattern.size(); row++) {
                        String line = pattern.get(row).getAsString();
                        for (int col = 0; col < GRID && col < line.length(); col++) {
                            char c = line.charAt(col);
                            lv[row * GRID + col] = (c >= '0' && c <= '7') ? (c - '0') : -1;
                        }
                    }
                    return lv;
                }
            }
            LOG.warn("[carving] shape {} not found, using fallback", rl);
        } catch (Exception e) {
            LOG.error("[carving] failed to load shape {}: {}", rl, e.toString());
        }
        // Fallback: a centered 6x6 block.
        int[] lv = new int[CELLS];
        java.util.Arrays.fill(lv, -1);
        for (int row = 5; row <= 10; row++) {
            for (int col = 5; col <= 10; col++) {
                lv[row * GRID + col] = 3;
            }
        }
        return lv;
    }
}
