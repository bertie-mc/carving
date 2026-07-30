package com.berlord.carving;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import net.neoforged.fml.loading.FMLPaths;

/**
 * Pack-supplied armor OUTPUT overrides. A modpack may ship
 * {@code config/berlords_carving-armor-overrides.json} mapping a carving material to explicit
 * armor item ids; a BIG-slate (armor) carve of that material then yields that item directly,
 * replacing the normal result (Slag part or vanilla armor). Tool carving is never affected.
 * Missing/invalid file or an unregistered target item = no override (normal behavior).
 *
 * <pre>{
 *   "wood": { "helmet": "immersive_armors:wooden_helmet", "chestplate": "...", ... },
 *   "bone": { ... }
 * }</pre>
 *
 * Keys are {@link CarvingMaterial#id} / {@link ArmorKind#id}. Read once, lazily (config files
 * are static per launch; packs relaunch to change mods/config anyway).
 */
public final class ArmorOverrides {
    private static volatile Map<CarvingMaterial, Map<ArmorKind, String>> overrides;

    private ArmorOverrides() {
    }

    /** The override item id for (material, kind), or null for normal carve behavior. */
    public static String get(CarvingMaterial material, ArmorKind kind) {
        Map<CarvingMaterial, Map<ArmorKind, String>> map = overrides;
        if (map == null) {
            synchronized (ArmorOverrides.class) {
                map = overrides;
                if (map == null) {
                    overrides = map = load();
                }
            }
        }
        Map<ArmorKind, String> kinds = map.get(material);
        return kinds == null ? null : kinds.get(kind);
    }

    private static Map<CarvingMaterial, Map<ArmorKind, String>> load() {
        Map<CarvingMaterial, Map<ArmorKind, String>> out = new EnumMap<>(CarvingMaterial.class);
        Path file = FMLPaths.CONFIGDIR.get().resolve("berlords_carving-armor-overrides.json");
        if (!Files.isRegularFile(file)) {
            return out;
        }
        try (Reader reader = Files.newBufferedReader(file)) {
            JsonObject root = new Gson().fromJson(reader, JsonObject.class);
            if (root == null) {
                return out;
            }
            for (CarvingMaterial m : CarvingMaterial.values()) {
                JsonElement el = root.get(m.id);
                if (el == null || !el.isJsonObject()) {
                    continue;
                }
                JsonObject kinds = el.getAsJsonObject();
                Map<ArmorKind, String> kindMap = new EnumMap<>(ArmorKind.class);
                for (ArmorKind k : ArmorKind.values()) {
                    JsonElement id = kinds.get(k.id);
                    if (id != null && id.isJsonPrimitive()) {
                        kindMap.put(k, id.getAsString().toLowerCase(Locale.ROOT));
                    }
                }
                if (!kindMap.isEmpty()) {
                    out.put(m, kindMap);
                }
            }
            if (!out.isEmpty()) {
                Carving.LOGGER.info("berlords_carving: loaded armor output overrides for {}", out.keySet());
            }
        } catch (Exception e) {
            Carving.LOGGER.error("berlords_carving: failed to read {} - ignoring overrides", file, e);
        }
        return out;
    }
}
