#!/usr/bin/env bash
# Cross-compiles proot (+ its talloc dependency) for Android arm64 with the NDK and installs
# the results as "native libraries" so Android extracts them to the app's nativeLibraryDir —
# the one place a targetSdk 29+ app may still execve() files from:
#
#   app/src/main/jniLibs/arm64-v8a/libproot.so         proot itself (a PIE executable)
#   app/src/main/jniLibs/arm64-v8a/libproot-loader.so  proot's static loader (PROOT_LOADER)
#
# The loader is unbundled on purpose: proot's default mode extracts the loader into a temp
# dir in app storage and executes it from there, which Android 10+ forbids for such apps.
#
# Usage: scripts/build-proot.sh <ndk-dir> <out-dir> [api-level]
set -euo pipefail

NDK_DIR="${1:?usage: build-proot.sh <ndk-dir> <out-dir> [api]}"
OUT_DIR="${2:?usage: build-proot.sh <ndk-dir> <out-dir> [api]}"
API="${3:-31}"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PROOT_SRC="$ROOT/third_party/proot/src"
TALLOC_SRC="$ROOT/third_party/talloc"
TALLOC_CFG="$ROOT/third_party/talloc-android"

TOOLS="$NDK_DIR/toolchains/llvm/prebuilt/linux-x86_64/bin"
[ -d "$TOOLS" ] || TOOLS="$NDK_DIR/toolchains/llvm/prebuilt/darwin-x86_64/bin"
CC="$TOOLS/aarch64-linux-android${API}-clang"
[ -x "$CC" ] || { echo "NDK clang not found: $CC" >&2; exit 1; }

WORK="$(mktemp -d "${TMPDIR:-/tmp}/proot-build.XXXXXX")"
trap 'rm -rf -- "$WORK"' EXIT

echo "==> talloc"
mkdir -p "$WORK/talloc"
"$CC" -c -O2 -fPIC -D__STDC_WANT_LIB_EXT1__=1 -DHAVE_CONFIG_H \
    -include "$TALLOC_CFG/config.h" -I"$TALLOC_CFG" -I"$TALLOC_SRC" -I"$TALLOC_SRC/lib/replace" \
    "$TALLOC_SRC/talloc.c" -o "$WORK/talloc/talloc.o"
"$CC" -c -O2 -fPIC "$TALLOC_CFG/talloc_compat.c" -o "$WORK/talloc/talloc_compat.o"
"$TOOLS/llvm-ar" rcs "$WORK/talloc/libtalloc.a" "$WORK/talloc/talloc.o" "$WORK/talloc/talloc_compat.o"

echo "==> proot"
cp -R "$PROOT_SRC" "$WORK/proot"
# readelf/strip/objcopy from the NDK's LLVM tools so the host needs no binutils.
mkdir -p "$WORK/bin"
ln -s "$TOOLS/llvm-readelf" "$WORK/bin/readelf"
MAKE_ARGS=(
    CC="$CC" \
    STRIP="$TOOLS/llvm-strip" OBJCOPY="$TOOLS/llvm-objcopy" OBJDUMP="$TOOLS/llvm-objdump" \
    CPPFLAGS="-D_FILE_OFFSET_BITS=64 -D_GNU_SOURCE -I. -I$TALLOC_SRC" \
    # Command-line CFLAGS replace the makefile's own `CFLAGS += -DPROOT_UNBUNDLE_LOADER=…`,
    # so the define has to be repeated here (PROOT_UNBUNDLE_LOADER= below only picks rules).
    CFLAGS="-Wall -Wextra -O2 -Wno-unused-parameter -DPROOT_UNBUNDLE_LOADER=\\\"/proot-loader-set-via-PROOT_LOADER\\\"" \
    LDFLAGS="-L$WORK/talloc -ltalloc -Wl,-z,noexecstack" \
    PROOT_UNBUNDLE_LOADER=/proot-loader-set-via-PROOT_LOADER \
    HAS_LOADER_32BIT= \
    V=0
)
JOBS="$(nproc 2>/dev/null || echo 4)"

# 1) The loader first: proot embeds the offset of its `pokedata_workaround` symbol.
PATH="$WORK/bin:$PATH" make -C "$WORK/proot" -j"$JOBS" loader/loader "${MAKE_ARGS[@]}"

# 2) loader-info.c — upstream generates it with a GNU-awk-only script (strtonum); do the
#    same with llvm-nm + shell arithmetic so any POSIX host works.
sym_addr() { "$TOOLS/llvm-nm" "$WORK/proot/loader/loader" | awk -v s="$1" '$3 == s { print $1; exit }'; }
POKE="$(sym_addr pokedata_workaround)"
START="$(sym_addr _start)"
[ -n "$POKE" ] && [ -n "$START" ] || { echo "loader symbols not found" >&2; exit 1; }
printf '#include <unistd.h>\nconst ssize_t offset_to_pokedata_workaround=%d;\n' \
    "$(( 16#$POKE - 16#$START ))" > "$WORK/proot/loader/loader-info.c"

# 3) proot itself.
PATH="$WORK/bin:$PATH" make -C "$WORK/proot" -j"$JOBS" proot "${MAKE_ARGS[@]}"

mkdir -p "$OUT_DIR"
"$TOOLS/llvm-strip" -o "$OUT_DIR/libproot.so" "$WORK/proot/proot"
"$TOOLS/llvm-strip" -o "$OUT_DIR/libproot-loader.so" "$WORK/proot/loader/loader"
chmod 755 "$OUT_DIR/libproot.so" "$OUT_DIR/libproot-loader.so"
echo "==> wrote $OUT_DIR/libproot.so and libproot-loader.so"
