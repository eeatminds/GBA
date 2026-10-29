# RetroLink

RetroLink is a free, open-source retro game emulator for Android with online play.
It is based on [Lemuroid](https://github.com/Swordfish90/Lemuroid) by Filippo Scognamiglio
and uses [libretro](https://www.libretro.com/) cores.

## What's new in RetroLink

- **Online "Play together" rooms**: 2 to 4 players play one multiplayer game over the internet
  (NES, SNES, Mega Drive/Genesis, Master System, Game Gear, Sega CD, PC Engine, Atari 2600/7800,
  PlayStation, N64 and arcade). Every phone runs the game in lockstep, inputs are exchanged each
  frame, and the host resends its game state if players drift apart.
- **Online "Trade & battle" rooms**: link Game Boy Advance games over the internet, as with a link
  cable or wireless adapter, using the [gpSP](https://github.com/libretro/gpsp) core's network link.
- Public rooms, private rooms with a 6-letter code, invite links and room reports.
- A small room server (`server/`) you can host for free.

## Building

```bash
git submodule update --init --depth 1
```

```bash
./gradlew :lemuroid-app:assemblePlayBundleDebug
```

Add `-Papp.abiFilters=arm64-v8a` for a smaller APK that runs on most phones. See
`docs/PUBLISHING.md` for release builds and the Play Store, and `server/README.md` for the room server.

## Tests

```bash
libretrodroid/src/test/cpp/run_tests.sh
```

```bash
cd server && npm test
```

### Supported Systems:
- Atari 2600 (A26) ([stella](https://docs.libretro.com/library/stella/))
- Atari 7800 (A78) ([prosystem](https://docs.libretro.com/library/prosystem/))
- Atari Lynx (Lynx) ([handy](https://docs.libretro.com/library/handy/))
- Nintendo (NES) ([fceumm](https://docs.libretro.com/library/fceumm/))
- Super Nintendo (SNES) ([snes9x](https://docs.libretro.com/library/snes9x/))
- Game Boy (GB) ([gambatte](https://docs.libretro.com/library/gambatte/))
- Game Boy Color (GBC) ([gambatte](https://docs.libretro.com/library/gambatte/))
- Game Boy Advance (GBA) ([mgba](https://docs.libretro.com/library/mgba/))
- Sega Genesis (aka Megadrive) ([genesis_plus_gx](https://docs.libretro.com/library/genesis_plus_gx/))
- Sega CD (aka Mega CD) ([genesis_plus_gx](https://docs.libretro.com/library/genesis_plus_gx/))
- Sega Master System (SMS) ([genesis_plus_gx](https://docs.libretro.com/library/genesis_plus_gx/))
- Sega Game Gear (GG) ([genesis_plus_gx](https://docs.libretro.com/library/genesis_plus_gx/))
- Nintendo 64 (N64) ([mupen64plus](https://docs.libretro.com/library/mupen64plus/))
- PlayStation (PSX) ([PCSX-ReARMed](https://docs.libretro.com/library/pcsx_rearmed/))
- PlayStation Portable (PSP) ([ppsspp](https://docs.libretro.com/library/ppsspp/))
- FinalBurn Neo (Arcade) ([fbneo](https://github.com/libretro/FBNeo/))
- Nintendo DS (NDS) ([desmume](https://docs.libretro.com/library/desmume/)/[MelonDS](https://docs.libretro.com/library/melonds/))
- NEC PC Engine (PCE) ([beetle_pce_fast](https://docs.libretro.com/library/beetle_pce_fast/))
- Neo Geo Pocket (NGP) ([mednafen_ngp](https://docs.libretro.com/library/beetle_neopop/))
- Neo Geo Pocket Color (NGC) ([mednafen_ngp](https://docs.libretro.com/library/beetle_neopop/))
- WonderSwan (WS) ([beetle_cygne](https://docs.libretro.com/library/beetle_cygne/))
- WonderSwan Color (WSC) ([beetle_cygne](https://docs.libretro.com/library/beetle_cygne/))
- Nintendo 3DS (3DS) ([citra](https://docs.libretro.com/library/citra/))

## Credits and license

RetroLink is licensed under the GNU General Public License v3.0 (see `COPYING`).

- [Lemuroid](https://github.com/Swordfish90/Lemuroid), Copyright (C) Filippo Scognamiglio and contributors, GPL-3.0
- [LibretroDroid](https://github.com/Swordfish90/LibretroDroid), Copyright (C) Filippo Scognamiglio, GPL-3.0
  (vendored in `libretrodroid/` with added netpacket and netplay support)
- [LemuroidCores](https://github.com/Swordfish90/LemuroidCores) and the libretro cores listed above, under their own licenses
- [gpSP](https://github.com/libretro/gpsp), GPL-2.0-or-later (see `extra-cores/README.md`)
- [Oboe](https://github.com/google/oboe), Apache-2.0, and [libretro-common](https://github.com/libretro/libretro-common), MIT

RetroLink does not include any games or BIOS files.
