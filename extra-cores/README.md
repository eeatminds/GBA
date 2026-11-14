# Extra cores

Emulator cores that are not part of [LemuroidCores](https://github.com/Swordfish90/LemuroidCores).

| Core | Used for | License | Source | Binary |
|---|---|---|---|---|
| gambatte (link) | Game Boy and Game Boy Color, with a network friendly link cable (whole-block exchanges for Red, Blue, Yellow, Gold, Silver and Crystal) | GPL-2.0 | https://github.com/libretro/gambatte-libretro (b752252, the version stock Lemuroid ships) plus `gambatte-link/0001-link-whole-blocks-and-sync-over-the-network.patch` | Built with NDK 26.1 (`ndk-build` in `libgambatte/libretro/jni`), `gambatte-link/jniLibs/<abi>/libgambatte_libretro_android.so`; replaces the stock gambatte in bundle builds |
| gpSP | Game Boy Advance online link play (link cable and wireless adapter over the internet) | GPL-2.0-or-later | https://github.com/libretro/gpsp (master, 5819380 or later) | libretro buildbot nightly, 2026-10-29, `gpsp_libretro_android.so` |
| melonDS DS | Nintendo DS with Wi-Fi (the games' own Wi-Fi Club over the internet, through a fan-run replacement of Nintendo's servers; an extra choice next to the default DS core) | GPL-3.0 | https://github.com/JesseTG/melonds-ds (1.4.0) | libretro buildbot nightly, 2026-11-11, `melondsds_libretro_android.so` (arm64-v8a, armeabi-v7a, x86_64; no x86 build exists), in `lemuroid_core_melondsds/src/main/jniLibs/<abi>/libmelondsds_libretro_android.so` |

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
- swaps each battle turn's move choice as one message, so a slow network can't mix up turns;
- meets the other game when both start linking: each sends a random number and waits until
  the other sends it back, so nothing left from an earlier try can pass for the partner (who
  drives the cable is agreed then, not by who started first);
- never holds up the frame while it waits: the game simply stays at that point, so the screen
  and sound keep going and the phone doesn't think the app froze;
- keeps its blocks while the connection to the app's link bridge is down for a moment, and
  waits, as on a cable, for a partner whose phone lost its connection (the bridge resends
  whatever was lost);
- when the bridge says the partner is gone for good: a link battle ends as if they ran, and in
  the trade center their answer reads as no / CANCEL, so nothing is traded without their yes;
- restarts the link only when its own options change (the app sets its options again each
  time it comes back to the front);
- lets a single byte transfer give up after 2.5 s (a real cable never waits), so a game that
  already left the link can't freeze the other.

To rebuild: clone gambatte-libretro at b752252 (the version stock Lemuroid ships: a different version saves states of another size, and old save states and auto-resume snapshots would no longer load), `git am` the patch file (it holds six commits), then run a clean build
`$ANDROID_NDK/ndk-build APP_ABI="armeabi-v7a arm64-v8a x86 x86_64" APP_PLATFORM=android-21 APP_CPPFLAGS="-std=c++11"`
in `libgambatte/libretro/jni` and copy `libs/<abi>/libretro.so` to
`gambatte-link/jniLibs/<abi>/libgambatte_libretro_android.so`.
