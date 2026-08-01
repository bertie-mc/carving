package com.berlord.carving;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourceDataTest {
    private static final String MOD_ID = "berlords_carving";
    private static final Path RESOURCES = Path.of(System.getProperty("bertie.projectDir"),
            "src", "main", "resources");

    @Test
    void everyJsonResourceParses() throws IOException {
        List<String> failures = new ArrayList<>();
        List<Path> jsonFiles;
        try (var files = Files.walk(RESOURCES)) {
            jsonFiles = files.filter(path -> path.toString().endsWith(".json")).toList();
        }
        for (Path path : jsonFiles) {
            try {
                JsonParser.parseString(Files.readString(path));
            } catch (RuntimeException failure) {
                failures.add(RESOURCES.relativize(path) + ": " + failure.getMessage());
            }
        }
        assertTrue(jsonFiles.size() >= 200, "expected the complete resource set");
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }

    @Test
    void everyPartShapeIsPresentAndUsesTheSixteenCellFormat() throws IOException {
        Path shapes = RESOURCES.resolve("assets/" + MOD_ID + "/shapes/slag");
        Set<String> expected = new HashSet<>();
        for (ToolKind kind : ToolKind.values()) {
            expected.add(kind.slagPart);
        }
        for (ArmorKind kind : ArmorKind.values()) {
            expected.add(kind.id);
        }

        Set<String> actual = new HashSet<>();
        try (var files = Files.list(shapes)) {
            for (Path path : files.filter(file -> file.toString().endsWith(".json")).toList()) {
                actual.add(path.getFileName().toString().replaceFirst("\\.json$", ""));
                var pattern = JsonParser.parseString(Files.readString(path)).getAsJsonObject()
                        .getAsJsonArray("pattern");
                assertEquals(16, pattern.size(), path.toString());
                boolean occupied = false;
                for (JsonElement row : pattern) {
                    String cells = row.getAsString();
                    assertEquals(16, cells.length(), path.toString());
                    assertTrue(cells.matches("[.0-7]{16}"), path.toString());
                    occupied |= !cells.equals("................");
                }
                assertTrue(occupied, path.toString());
            }
        }
        assertEquals(expected, actual);
    }

    @Test
    void localModelTexturesExist() throws IOException {
        Path models = RESOURCES.resolve("assets/" + MOD_ID + "/models");
        List<String> missing = new ArrayList<>();
        try (var files = Files.walk(models)) {
            for (Path model : files.filter(path -> path.toString().endsWith(".json")).toList()) {
                JsonObject json = JsonParser.parseString(Files.readString(model)).getAsJsonObject();
                if (!json.has("textures")) {
                    continue;
                }
                for (JsonElement texture : json.getAsJsonObject("textures").asMap().values()) {
                    String id = texture.getAsString();
                    if (!id.startsWith(MOD_ID + ":")) {
                        continue;
                    }
                    Path image = RESOURCES.resolve("assets/" + MOD_ID + "/textures")
                            .resolve(id.substring(MOD_ID.length() + 1) + ".png");
                    if (!Files.isRegularFile(image)) {
                        missing.add(RESOURCES.relativize(model) + " -> " + RESOURCES.relativize(image));
                    }
                }
            }
        }
        assertTrue(missing.isEmpty(), String.join("\n", missing));
    }

    @Test
    void allSlagCraftingTablePartRecipesAreDisabled() throws IOException {
        Path overrides = RESOURCES.resolve("data/slag/recipe/crafting/parts");
        List<Path> recipes;
        try (var files = Files.list(overrides)) {
            recipes = files.filter(path -> path.toString().endsWith(".json")).toList();
        }
        assertEquals(144, recipes.size());
        for (Path recipe : recipes) {
            JsonObject json = JsonParser.parseString(Files.readString(recipe)).getAsJsonObject();
            var conditions = json.getAsJsonArray("neoforge:conditions");
            assertFalse(conditions.isEmpty(), recipe.toString());
            assertEquals("neoforge:false",
                    conditions.get(0).getAsJsonObject().get("type").getAsString(), recipe.toString());
        }
    }
}
