package com.berlord.carving;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ArmorOverridesTest {

    @Test
    void parsesKnownStringEntriesAndNormalizesItemIds() {
        var parsed = ArmorOverrides.parse(JsonParser.parseString("""
                {
                  "wood": {
                    "helmet": "IMMERSIVE_ARMORS:WOODEN_HELMET",
                    "boots": 7,
                    "unknown": "example:ignored"
                  },
                  "unknown_material": {"helmet": "example:ignored"}
                }
                """).getAsJsonObject());

        assertEquals("immersive_armors:wooden_helmet",
                parsed.get(CarvingMaterial.WOOD).get(ArmorKind.HELMET));
        assertFalse(parsed.get(CarvingMaterial.WOOD).containsKey(ArmorKind.BOOTS));
        assertEquals(1, parsed.size());
    }
}
