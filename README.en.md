# LogisticsCraft²: The New Era (`lc2`)

[简体中文](README.md) · **English**

**LogisticsCraft²: The New Era (`lc2`)** is a maritime and aerial logistics addon for [IndustrialCraft²: The New Era](https://github.com/spectrum527/IndustrialCraft2-The-New-Era), featuring autonomous electric cargo boats, standard & heavy transport drones, Create-train-style unloaded-chunk transit, collision avoidance, and deep IC2 EU & machine-upgrade integration.

## Downloads & Installation

Requires **IndustrialCraft²: The New Era (`ic2` v1.0.5+)** for the matching Minecraft version. Place the matching `lc2` JAR into `mods/` on both client and server.

[v1.1.0 Release Notes](https://github.com/spectrum527/LogisticsCraft2-The-New-Era/releases/tag/v1.1.0) · [SHA-256 Checksums](https://github.com/spectrum527/LogisticsCraft2-The-New-Era/releases/download/v1.1.0/SHA256SUMS.txt)

| Minecraft | Loader | Java | Download |
| --- | --- | ---: | --- |
| 1.19.2 | Forge 43.5.0 | 17 | [Forge 1.19.2 JAR](https://github.com/spectrum527/LogisticsCraft2-The-New-Era/releases/download/v1.1.0/lc2-forge-1.19.2-1.1.0.jar) |
| 1.21.1 | NeoForge 21.1.265 | 21 | [NeoForge 1.21.1 JAR](https://github.com/spectrum527/LogisticsCraft2-The-New-Era/releases/download/v1.1.0/lc2-neoforge-1.21.1-1.1.0.jar) |
| 26.1.2 | NeoForge 26.1.2.128 | 25 | [NeoForge 26.1.2 JAR](https://github.com/spectrum527/LogisticsCraft2-The-New-Era/releases/download/v1.1.0/lc2-neoforge-26.1.2-1.1.0.jar) |
| 26.2 | NeoForge 26.2.0.95 | 25 | [NeoForge 26.2 JAR](https://github.com/spectrum527/LogisticsCraft2-The-New-Era/releases/download/v1.1.0/lc2-neoforge-26.2-1.1.0.jar) |

## Features

- **Strict 2-Zone Cargo Isolation & Swap (`Smart Port` & `Cargo Station`)**: Each station provides a dedicated **27-slot Outbound Zone** (fed by external pipes/hoppers & IC2 Pulling Upgrades; exclusively loaded onto departing boats/drones; measured by Redstone Comparators) and a **27-slot Storage Zone** (receives unloaded deliveries; exclusively extracted by external pipes/hoppers & IC2 Ejector Upgrades), plus an instant **Swap (`⇄`)** button between both zones.
- **Waterway Logistics**: **Smart Port (`lc2:smart_port`)** + **Electric Smart Cargo Boat (`lc2:electric_cargo_boat`)** with 54-slot Double Chest capacity, verified A* navigable water routing & collision avoidance, Chest/Shulker/IC2 Storage Box automation, 14,400 EU buffer + dedicated single-use battery slot (`3,000 EU` / `2` batteries one-way, doubled for round-trip), and boarding lock during active transit (players who board before dispatch ride along!).
- **Airway Logistics**: **Cargo Station (`lc2:cargo_station`)** with 6 visual drone pads, **Transport Drone** (12 slots, fast) & **Heavy Transport Drone** (36 slots, 2× cost), **RF Chips I/II/III** (256 / 512 / 1024 Manhattan distance), tiered distance pricing (`800 / 2,000 / 7,000 EU` or `1 / 2 / 5` single-use batteries; 2× heavy), atomic target bay reservation, target-paid auto-return, and 3D altitude-stratified VTOL collision avoidance.
- **Unloaded-Chunk Transit & Upgrades**: All boats and drones operate seamlessly across unloaded chunks and survive restarts; **Chunk Loading Upgrade (`lc2:chunk_loading_upgrade`)** keeps a 3×3 chunk area loaded around powered ports/stations at 2 EU/t; full compatibility with IC2 EU cables, LV base input, Transformer, Energy Storage, Overclocker, Ejector/Advanced Ejector, Pulling/Advanced Pulling, and Redstone Inverter upgrades.

## Building from Source

Place the four IC2 v1.0.5 JARs inside `.ic2-deps/`, then run:

```sh
# Core unit tests + NeoForge 1.21.1 / 26.1.2 / 26.2 JARs
./gradlew :checkCommonImports :test:test :neoforge:1.21.1:jar :neoforge:26.1.2:jar :neoforge:26.2:jar

# Forge 1.19.2 reobfuscated JAR (Java 17)
./forge-1.19.2/gradlew -p forge-1.19.2 build reobfJar
```

## License

Code is licensed under [GPL-3.0-only](LICENSE). Original authored textures are licensed under CC BY-SA 4.0.
