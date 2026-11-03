# Extra cores

Emulator cores that are not part of [LemuroidCores](https://github.com/Swordfish90/LemuroidCores).

| Core | Used for | License | Source | Binary |
|---|---|---|---|---|
| gambatte (link) | Game Boy and Game Boy Color, with a network friendly link cable (whole-block exchanges for Gold, Silver and Crystal) | GPL-2.0 | https://github.com/libretro/gambatte-libretro (master, 77f2567) plus `gambatte-link/0001-link-whole-blocks-and-sync-over-the-network.patch` | Built with NDK 26.1 (`ndk-build` in `libgambatte/libretro/jni`), `gambatte-link/jniLibs/<abi>/libgambatte_libretro_android.so`; replaces the stock gambatte in bundle builds |
| gpSP | Game Boy Advance online link play (link cable and wireless adapter over the internet) | GPL-2.0-or-later | https://github.com/libretro/gpsp (master, 5819380 or later) | libretro buildbot nightly, 2026-10-29, `gpsp_libretro_android.so` |

To update gpSP, download `gpsp_libretro_android.so.zip` for each ABI from
`https://buildbot.libretro.com/nightly/android/latest/<abi>/` and save it as
`lemuroid_core_gpsp/src/main/jniLibs/<abi>/libgpsp_libretro_android.so`.

## gambatte (link)

The stock link cable over a network sends one byte at a time and waits for the other phone's
answer each time, so Pokémon Gold, Silver and Crystal take minutes to open a trade over the
internet, and their frame-counted link checks time out ("Too much time has elapsed"). The patch:

- sends the four big exchanges of the link session (random numbers, party, patch lists, mail)
  as one block each, at the calls Nintendo's own wireless re-release hooked (the core checks the
  ROM bytes there before using them);
- turns the games' "both ready?" routine (`WaitLinkTransfer`) into a wait for the other side's
  answer, however long the network takes;
- lets a single byte transfer give up after 2.5 s (a real cable never waits), so a game that
  already left the link can't freeze the other.

To rebuild: clone gambatte-libretro at 77f2567, `git am` the patch, then run
`$ANDROID_NDK/ndk-build APP_ABI="armeabi-v7a arm64-v8a x86 x86_64" APP_PLATFORM=android-21`
in `libgambatte/libretro/jni` and copy `libs/<abi>/libretro.so` to
`gambatte-link/jniLibs/<abi>/libgambatte_libretro_android.so`.
