# Berlord's Carving

Carve early-game tool heads and armor from material slates: place the head inside a block of material and drag to carve it away. Includes optional Slag 'n' Embers and EMI integration.

- **Minecraft:** 1.21.1
- **Loader:** NeoForge
- **Mod ID:** `berlords_carving`

## Install
Download the latest JAR from the [Releases page](../../releases) and put it in your `mods/` folder. Requires NeoForge for Minecraft 1.21.1. Slag 'n' Embers and EMI are optional — install them for extra integration, or run standalone.

## Integration & credits

Works standalone with its own textures. When **Slag 'n' Embers** (by LopyLuna) is installed, Carving integrates with it and uses Slag's parts and art **at runtime**, loaded from your installed copy of Slag — this repository does not contain or redistribute any Slag assets. Slag 'n' Embers is All Rights Reserved.

## Building
`./gradlew build` — the built JAR is written to `build/libs/`. Optional dependencies (Slag 'n' Embers, EMI) are resolved from Modrinth, so the build works without any local jars.

## License

Released under the MIT License — see [LICENSE](LICENSE).
