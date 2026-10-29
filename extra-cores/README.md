# Extra cores

Emulator cores that are not part of [LemuroidCores](https://github.com/Swordfish90/LemuroidCores).

| Core | Used for | License | Source | Binary |
|---|---|---|---|---|
| gpSP | Game Boy Advance online link play (link cable and wireless adapter over the internet) | GPL-2.0-or-later | https://github.com/libretro/gpsp (master, 5819380 or later) | libretro buildbot nightly, 2026-10-29, `gpsp_libretro_android.so` |

To update gpSP, download `gpsp_libretro_android.so.zip` for each ABI from
`https://buildbot.libretro.com/nightly/android/latest/<abi>/` and save it as
`lemuroid_core_gpsp/src/main/jniLibs/<abi>/libgpsp_libretro_android.so`.
