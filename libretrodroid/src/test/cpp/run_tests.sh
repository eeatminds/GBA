#!/bin/sh
# Builds and runs the host-side netpacket tests (needs a C++17 compiler).
set -e
DIR="$(cd "$(dirname "$0")" && pwd)"
OUT="${TMPDIR:-/tmp}/netpacket_test"
c++ -std=c++17 -Wall -Wno-unused-function -pthread -I"$DIR/shim" \
    "$DIR/netpacket_test.cpp" "$DIR/../../main/cpp/netpacket.cpp" -o "$OUT"
"$OUT"
