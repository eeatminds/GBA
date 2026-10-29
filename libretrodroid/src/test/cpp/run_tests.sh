#!/bin/sh
# Builds and runs the host-side tests for the online play code (needs a C++17 compiler and zlib).
set -e
DIR="$(cd "$(dirname "$0")" && pwd)"
SRC="$DIR/../../main/cpp"
OUT="${TMPDIR:-/tmp}"
FLAGS="-std=c++17 -Wall -Wno-unused-function -pthread -I$DIR/shim -I$SRC/libretro/libretro-common/include"

c++ $FLAGS "$DIR/netpacket_test.cpp" "$SRC/netpacket.cpp" -o "$OUT/netpacket_test"
"$OUT/netpacket_test"

c++ $FLAGS "$DIR/netplay_test.cpp" "$SRC/netplay.cpp" -lz -o "$OUT/netplay_test"
"$OUT/netplay_test"
