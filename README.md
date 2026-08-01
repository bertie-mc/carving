> **Development has moved:** See [the `carving` module in the Bertie monorepo](https://github.com/bertie-mc/bertie/tree/main/mods/carving). This repository is retained read-only for historical tags, releases, and issues.

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
Enter the shared environment with `nix develop ../bertie-ci` from the standard workspace (or
`nix develop github:bertie-mc/bertie-ci` from a standalone clone), then run `gradle build`; the JAR
is written to `build/libs/`. Optional dependencies are resolved from Modrinth, so no local jars are
required.

`gradle test` checks the material and network-index contracts, armor overrides, packaged JSON,
models, textures, shapes, and Slag recipe replacements. `gradle runGameTestServer` exercises the
waterlogged carving station and its inventory. CI additionally launches a client with EMI and Slag,
verifies all 148 integration recipes register, and joins a world.

## License

Released into the public domain under **The Unlicense** — see [UNLICENSE](UNLICENSE). Third-party assets and dependencies are carved out in [NOTICE](NOTICE).
