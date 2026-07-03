#!/usr/bin/env python3
"""Single source of truth for Berlord's Carving assets.

Carving now produces the FINAL item directly (no Berlord's intermediate item):
  - Slag present (+ material has a Slag id) -> a slag:dynamic_part (built in Java, SlagCompat).
  - Slag absent OR leather               -> the full vanilla tool/armor (built in Java).
So this pipeline only emits: slate textures/models, the two carving-shape sets, the flint block,
the carving-station item model, lang, and recipes (slates + flint + station + Slag part-recipe
removals). NO head/armor item textures, NO to_part/assemble recipes.

Shapes (silhouettes) come in two sets and the screen picks by Slag presence:
  shapes/slag/<part>.json    from Slag's greyscale part templates (pickaxe_head..sword_blade, helmet..boots)
  shapes/vanilla/<kind>.json from the vanilla item icons          (pickaxe..sword,       helmet..boots)

Run:  python make_assets.py
"""
import json
import os
import shutil
from PIL import Image

MODID = "berlords_carving"
GRID = 16

BASE = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
ASSETS = os.path.join(BASE, "src", "main", "resources", "assets", MODID)
DATA = os.path.join(BASE, "src", "main", "resources", "data", MODID)
SLAG = os.path.join(os.path.dirname(__file__), "slag-ref")
PAL = os.path.join(SLAG, "palettes")
PAL_ARMOR = os.path.join(SLAG, "palettes_armor")
VANILLA = os.path.join(os.path.dirname(__file__), "vanilla-icons")
SLAG_DATA = os.path.join(BASE, "src", "main", "resources", "data", "slag")

# id -> (slag_id|None, tier, vanilla_tool|None, vanilla_armor|None, has_tools, display)
MATERIALS = {
    "wood":       ("wooden",     1, "wooden",  None,      True,  "Wooden"),
    "stone":      ("stone",      1, "stone",   None,      True,  "Stone"),
    "flint":      ("flint",      1, None,      None,      True,  "Flint"),
    "bone":       ("bone",       1, None,      None,      True,  "Bone"),
    "leather":    (None,         1, None,      "leather", False, "Leather"),
    "copper":     ("copper",     2, None,      None,      True,  "Copper"),
    "iron":       ("iron",       2, "iron",    "iron",    True,  "Iron"),
    "golden":     ("golden",     2, "golden",  "golden",  True,  "Golden"),
    "diamond":    ("diamond",    2, "diamond", "diamond", True,  "Diamond"),
    "emerald":    ("emerald",    2, None,      None,      True,  "Emerald"),
    "amethyst":   ("amethyst",   2, None,      None,      True,  "Amethyst"),
    "lapis":      ("lapis",      2, None,      None,      True,  "Lapis"),
    "quartz":     ("quartz",     2, None,      None,      True,  "Quartz"),
    "obsidian":   ("obsidian",   2, None,      None,      True,  "Obsidian"),
    "echo":       ("echo",       2, None,      None,      True,  "Echo"),
    "deep_alloy": ("deep_alloy", 2, None,      None,      True,  "Deep Alloy"),
    "rose_gold":  ("rose_gold",  2, None,      None,      True,  "Rose Gold"),
}
SLAG_ID   = {m: v[0] for m, v in MATERIALS.items()}
V_TOOL    = {m: v[2] for m, v in MATERIALS.items()}
V_ARMOR   = {m: v[3] for m, v in MATERIALS.items()}
HAS_TOOLS = {m: v[4] for m, v in MATERIALS.items()}
DISPLAY   = {m: v[5] for m, v in MATERIALS.items()}

TOOLS = ["pickaxe", "axe", "shovel", "hoe", "sword"]
TOOL_PART = {"pickaxe": "pickaxe_head", "axe": "axe_head", "shovel": "shovel_head",
             "hoe": "hoe_head", "sword": "sword_blade"}
ARMORS = ["helmet", "chestplate", "leggings", "boots"]

# Slag ships craftable crafting-table recipes for the tool heads AND armor of ALL 16 materials
# (verified in-jar: crafting/parts/<part>_<mat>.json for every material). Disable them all so carving
# is the route when Slag is present; Slag's CASTING (smeltery) path + plate/guard are left untouched.
SLAG_ARMOR_CRAFT_MATS = [SLAG_ID[m] for m in MATERIALS if SLAG_ID[m]]
SLAG_TOOL_CRAFT_MATS = SLAG_ARMOR_CRAFT_MATS

SMALL_INGREDIENT = {
    "wood": {"tag": "minecraft:planks"}, "stone": {"item": "minecraft:cobblestone"},
    "flint": {"item": "minecraft:flint"}, "bone": {"item": "minecraft:bone"},
    "iron": {"tag": "c:ingots/iron"}, "golden": {"tag": "c:ingots/gold"},
    "copper": {"tag": "c:ingots/copper"}, "diamond": {"tag": "c:gems/diamond"},
    "emerald": {"tag": "c:gems/emerald"}, "amethyst": {"item": "minecraft:amethyst_shard"},
    "lapis": {"item": "minecraft:lapis_lazuli"}, "quartz": {"item": "minecraft:quartz"},
    "obsidian": {"item": "minecraft:obsidian"}, "echo": {"item": "minecraft:echo_shard"},
    "deep_alloy": {"tag": "c:ingots/deep_alloy"}, "rose_gold": {"tag": "c:ingots/rose_gold"},
}
BIG_INGREDIENT = {
    "wood": {"tag": "minecraft:logs"}, "stone": {"item": "minecraft:stone"},
    "flint": {"item": f"{MODID}:flint_block"}, "bone": {"item": "minecraft:bone_block"},
    "iron": {"tag": "c:storage_blocks/iron"}, "golden": {"tag": "c:storage_blocks/gold"},
    "copper": {"tag": "c:storage_blocks/copper"}, "diamond": {"tag": "c:storage_blocks/diamond"},
    "emerald": {"tag": "c:storage_blocks/emerald"}, "amethyst": {"item": "minecraft:amethyst_block"},
    "lapis": {"tag": "c:storage_blocks/lapis"}, "quartz": {"item": "minecraft:quartz_block"},
    "obsidian": {"item": "minecraft:crying_obsidian"}, "echo": {"item": "minecraft:echo_shard"},
    "deep_alloy": {"tag": "c:storage_blocks/deep_alloy"}, "rose_gold": {"tag": "c:storage_blocks/rose_gold"},
    "leather": {"item": "minecraft:leather"},
}

MOD_LOADED_SLAG = {"type": "neoforge:mod_loaded", "modid": "slag"}
COND_FALSE = {"type": "neoforge:false"}


def load_strip(path):
    im = Image.open(path).convert("RGBA")
    px = im.load()
    return [px[i, 0] for i in range(im.width)]


def leather_palette(n):
    dark, light = (74, 53, 32), (199, 159, 112)
    return [tuple(round(dark[k] + (light[k] - dark[k]) * (i / max(1, n - 1))) for k in range(3)) + (255,)
            for i in range(n)]


def slate_colours(palette):
    n = len(palette)
    pick = lambda i: palette[max(0, min(n - 1, i))][:3]
    return pick(n // 2), pick(n - 1), pick(n // 3), pick(max(0, n // 6))


def render_slate(cols, big):
    base, light, dark, outline = cols
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    px = img.load()
    lo, hi = (1, 14) if big else (2, 13)
    for y in range(lo, hi + 1):
        for x in range(lo, hi + 1):
            if x in (lo, hi) or y in (lo, hi):
                col = outline
            elif big and (x in (lo + 1, hi - 1) or y in (lo + 1, hi - 1)):
                col = outline
            else:
                col = base
                if x == hi - 1 or y == hi - 1:
                    col = dark
                if x == lo + 1 or y == lo + 1:
                    col = light
            px[x, y] = (col[0], col[1], col[2], 255)
    span = range(lo + (2 if big else 1), hi - (1 if big else 0))
    for t in span:
        if px[8, t][3]:
            px[8, t] = (dark[0], dark[1], dark[2], 255)
        if px[t, 8][3]:
            px[t, 8] = (dark[0], dark[1], dark[2], 255)
    return img


def shape_rows(img):
    """16x16 form map by luminance: '.' = empty (carve away), else '0'-'7' = level (keep)."""
    img = img.convert("RGBA")
    sp = img.load()
    w, h = img.size
    rows = []
    for y in range(GRID):
        row = ""
        for x in range(GRID):
            r = g = b = 0
            a = 0
            if x < w and y < h:
                r, g, b, a = sp[x, y]
            if a <= 16:
                row += "."
            else:
                lum = (r * 299 + g * 587 + b * 114) // 1000
                row += str(min(7, lum // 32))
        rows.append(row)
    return rows


def write_json(path, obj):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(obj, f, indent=2)
        f.write("\n")


def gen_item_model(name, parent="minecraft:item/generated", layer=True):
    obj = {"parent": parent}
    if layer:
        obj["textures"] = {"layer0": f"{MODID}:item/{name}"}
    write_json(os.path.join(ASSETS, "models", "item", f"{name}.json"), obj)


def clean():
    for d in [os.path.join(ASSETS, "textures", "item"), os.path.join(ASSETS, "models", "item"),
              os.path.join(ASSETS, "shapes"), os.path.join(DATA, "recipe"),
              os.path.join(SLAG_DATA, "recipe", "crafting", "parts")]:
        if os.path.isdir(d):
            shutil.rmtree(d)
        os.makedirs(d, exist_ok=True)


def main():
    clean()
    tex = os.path.join(ASSETS, "textures", "item")
    os.makedirs(tex, exist_ok=True)
    lang = {}

    # ---- shapes: slag part set + vanilla item set --------------------------
    for tool in TOOLS:
        part = TOOL_PART[tool]
        write_json(os.path.join(ASSETS, "shapes", "slag", f"{part}.json"),
                   {"pattern": shape_rows(Image.open(os.path.join(SLAG, f"dyn_base_{part}.png")))})
        write_json(os.path.join(ASSETS, "shapes", "vanilla", f"{tool}.json"),
                   {"pattern": shape_rows(Image.open(os.path.join(VANILLA, f"iron_{tool}.png")))})
    for piece in ARMORS:
        write_json(os.path.join(ASSETS, "shapes", "slag", f"{piece}.json"),
                   {"pattern": shape_rows(Image.open(os.path.join(SLAG, f"armor_base_{piece}.png")))})
        write_json(os.path.join(ASSETS, "shapes", "vanilla", f"{piece}.json"),
                   {"pattern": shape_rows(Image.open(os.path.join(VANILLA, f"iron_{piece}.png")))})

    # ---- slate textures/models/lang ---------------------------------------
    armor_base = load_strip(os.path.join(PAL_ARMOR, "base_palette.png"))
    for mat in MATERIALS:
        slag = SLAG_ID[mat]
        if HAS_TOOLS[mat]:
            tpal = load_strip(os.path.join(PAL, slag + ".png"))
            render_slate(slate_colours(tpal), big=False).save(os.path.join(tex, f"{mat}_slate.png"))
            gen_item_model(f"{mat}_slate")
            lang[f"item.{MODID}.{mat}_slate"] = f"Small {DISPLAY[mat]} Slate"
        apal = leather_palette(len(armor_base)) if slag is None else load_strip(os.path.join(PAL_ARMOR, slag + ".png"))
        render_slate(slate_colours(apal), big=True).save(os.path.join(tex, f"{mat}_big_slate.png"))
        gen_item_model(f"{mat}_big_slate")
        lang[f"item.{MODID}.{mat}_big_slate"] = f"Big {DISPLAY[mat]} Slate"

    # ---- flint block + carving station item model -------------------------
    write_json(os.path.join(ASSETS, "models", "block", "flint_block.json"),
               {"parent": "minecraft:block/cube_all", "textures": {"all": f"{MODID}:block/flint_surface"}})
    write_json(os.path.join(ASSETS, "blockstates", "flint_block.json"),
               {"variants": {"": {"model": f"{MODID}:block/flint_block"}}})
    gen_item_model("flint_block", parent=f"{MODID}:block/flint_block", layer=False)
    write_json(os.path.join(DATA, "loot_table", "blocks", "flint_block.json"), {
        "type": "minecraft:block",
        "pools": [{"rolls": 1, "entries": [{"type": "minecraft:item", "name": f"{MODID}:flint_block"}],
                   "conditions": [{"condition": "minecraft:survives_explosion"}]}]})
    lang[f"block.{MODID}.flint_block"] = "Block of Flint"
    # carving station item model (block model is hand-maintained); fixes the missing item texture
    gen_item_model("carving_station", parent=f"{MODID}:block/carving_station", layer=False)
    lang[f"block.{MODID}.carving_station"] = "Carving Station"

    # ---- misc lang --------------------------------------------------------
    lang[f"{MODID}.screen.title"] = "Carving"
    lang[f"{MODID}.screen.slips"] = "Errors: %s/3"
    lang[f"{MODID}.screen.hint"] = "Click to carve the shape"
    lang[f"{MODID}.screen.cancel"] = "Leaving ruins the slate"
    lang[f"{MODID}.station.insert"] = "Insert a Tier 2 slate"
    lang[f"{MODID}.station.controls"] = "WS move · AD rotate · Space cut"
    lang[f"{MODID}.station.needs_water"] = "needs water"
    lang[f"{MODID}.station.zoom"] = "%sx"
    lang[f"{MODID}.quality.chipped"] = "Chipped %s"
    lang[f"{MODID}.quality.flawed"] = "Flawed %s"
    lang[f"{MODID}.tier.1"] = "TIER 1"
    lang[f"{MODID}.tier.2"] = "TIER 2"
    write_json(os.path.join(ASSETS, "lang", "en_us.json"), dict(sorted(lang.items())))

    # ---- recipes ----------------------------------------------------------
    rec = os.path.join(DATA, "recipe")
    n_small = n_big = 0
    for mat in MATERIALS:
        if HAS_TOOLS[mat]:
            r = {"type": "minecraft:crafting_shaped", "pattern": ["##", "##"],
                 "key": {"#": SMALL_INGREDIENT[mat]}, "result": {"id": f"{MODID}:{mat}_slate", "count": 1}}
            if V_TOOL[mat] is None:
                r["neoforge:conditions"] = [MOD_LOADED_SLAG]
            write_json(os.path.join(rec, f"{mat}_slate.json"), r)
            n_small += 1
        # Echo has no block form (reinforced deepslate is uncraftable), so its big slate is a
        # donut of 8 echo shards (3x3 ring, empty centre) instead of the standard 2x2 block.
        big_pattern = ["###", "# #", "###"] if mat == "echo" else ["##", "##"]
        rb = {"type": "minecraft:crafting_shaped", "pattern": big_pattern,
              "key": {"#": BIG_INGREDIENT[mat]}, "result": {"id": f"{MODID}:{mat}_big_slate", "count": 1}}
        if mat != "leather" and V_ARMOR[mat] is None:
            rb["neoforge:conditions"] = [MOD_LOADED_SLAG]
        write_json(os.path.join(rec, f"{mat}_big_slate.json"), rb)
        n_big += 1

    write_json(os.path.join(rec, "flint_block.json"), {
        "type": "minecraft:crafting_shaped", "pattern": ["FFF", "FFF", "FFF"],
        "key": {"F": {"item": "minecraft:flint"}}, "result": {"id": f"{MODID}:flint_block", "count": 1}})
    write_json(os.path.join(rec, "flint_from_block.json"), {
        "type": "minecraft:crafting_shapeless", "ingredients": [{"item": f"{MODID}:flint_block"}],
        "result": {"id": "minecraft:flint", "count": 9}})
    write_json(os.path.join(rec, "carving_station.json"), {
        "type": "minecraft:crafting_shaped", "pattern": ["AB", "CC"],
        "key": {"A": {"item": "minecraft:water_bucket"}, "B": {"item": "minecraft:amethyst_shard"},
                "C": {"item": "minecraft:chiseled_deepslate"}},
        "result": {"id": f"{MODID}:carving_station", "count": 1}})

    # remove Slag's own crafting/parts recipes for the parts carving replaces (leave plate/guard + casting)
    parts_dir = os.path.join(SLAG_DATA, "recipe", "crafting", "parts")
    n_over = 0
    overrides = ([(TOOL_PART[t], sm) for t in TOOLS for sm in SLAG_TOOL_CRAFT_MATS]
                 + [(p, sm) for p in ARMORS for sm in SLAG_ARMOR_CRAFT_MATS])
    for part, sm in overrides:
        write_json(os.path.join(parts_dir, f"{part}_{sm}.json"), {
            "neoforge:conditions": [COND_FALSE], "type": "minecraft:crafting_shapeless",
            "ingredients": [{"item": "minecraft:stick"}], "result": {"id": "minecraft:stick"}})
        n_over += 1

    print(f"modid: {MODID}")
    print(f"  shapes: 9 slag + 9 vanilla   slates: small={n_small} big={n_big}")
    print(f"  recipes: small={n_small} big={n_big} flint=2 station=1 slag_overrides={n_over}")
    print(f"  (no head/armor item textures; carving builds slag parts / vanilla items directly)")


if __name__ == "__main__":
    main()
