### PinataParty for Folia

This is a project that contains per-file patches for the popular [PinataParty](https://www.spigotmc.org/resources/59318/) plugin,
which adds support for [Folia](https://github.com/PaperMC/Folia).

## Usage

> [!NOTE]
> You need to provide your own copy of PinataParty.

1. Place the PinataParty jar in the `sources/` directory. Your file should be named `PinataParty-*.jar`.
2. Run `./patcher.sh setup` to download VineFlower, decompile, and apply the patches.
3. Run `./gradlew build` to get an executable jar in `build/libs/` directory.

## Licenses

This repository contains original patch files authored by LumaLibre.
These patch files and the tooling to apply these patch files are licensed under the MIT License.
The underlying software is not included and remains the property of its respective copyright holder.