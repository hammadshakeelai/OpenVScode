#!/bin/bash
#
# Rewrites every ELF in an exported rootfs so it can be executed directly on
# Android.
#
# The problem: a Debian binary records an absolute interpreter path in its ELF
# header — /lib64/ld-linux-x86-64.so.2 — and nothing exists there on Android.
# An app cannot create it either, since / is read-only. Exec therefore fails
# with ENOENT even though targetSdk 28 permits the exec itself.
#
# The usual workaround is to invoke the loader explicitly:
#
#     ld-linux.so --library-path ... /path/to/node app.js
#
# That does run — it is how this was first proven, and bash, python3 and node
# all started that way. But it breaks anything that re-executes itself, because
# /proc/self/exe and process.execPath then point at the *loader* instead of the
# program. code-server spawns exactly such a child and dies with "cannot open
# shared object file". Measured on an emulator, not theorised.
#
# So instead: patch the recorded interpreter and RPATH to absolute paths inside
# the installed rootfs, the way Nix does for its store. Binaries then exec
# directly, self-reexec works, children need nothing special, and no ptrace
# supervisor is required at runtime.
#
# This runs on the CI host against the *extracted* filesystem, deliberately.
# Running it inside the container would have it patch bash, find and patchelf
# while it was using them, and the loop would break partway through.
#
# The cost is that the install location is fixed at build time. It is
# deterministic for a given package id, so that is acceptable.
set -euo pipefail

TREE="${1:?usage: patch-elf.sh <extracted-tree> <install-path> <arch>}"
ROOTFS_PATH="${2:?missing install path}"
ARCH="${3:?missing arch (arm64|amd64)}"

case "$ARCH" in
    arm64) TRIPLET=aarch64-linux-gnu; LOADER_NAME=ld-linux-aarch64.so.1 ;;
    amd64) TRIPLET=x86_64-linux-gnu;  LOADER_NAME=ld-linux-x86-64.so.2 ;;
    *) echo "unsupported arch: $ARCH" >&2; exit 1 ;;
esac

LOADER="$ROOTFS_PATH/lib/$TRIPLET/$LOADER_NAME"

# Most specific library directories first.
RPATH="$ROOTFS_PATH/lib/$TRIPLET"
RPATH="$RPATH:$ROOTFS_PATH/usr/lib/$TRIPLET"
RPATH="$RPATH:$ROOTFS_PATH/lib"
RPATH="$RPATH:$ROOTFS_PATH/usr/lib"
RPATH="$RPATH:$ROOTFS_PATH/usr/local/lib"
RPATH="$RPATH:$ROOTFS_PATH/usr/lib/llvm-14/lib"
RPATH="$RPATH:$ROOTFS_PATH/opt/code-server/lib"

echo "patching ELFs in $TREE"
echo "  install path: $ROOTFS_PATH"
echo "  loader:       $LOADER"

if [ ! -f "$TREE/lib/$TRIPLET/$LOADER_NAME" ]; then
    echo "ERROR: loader not found at $TREE/lib/$TRIPLET/$LOADER_NAME" >&2
    exit 1
fi

patched_exec=0
patched_lib=0
skipped=0

while IFS= read -r -d '' f; do
    # Cheap ELF magic test; running `file` across ~40k entries is far slower.
    if [ "$(head -c 4 "$f" 2>/dev/null | od -An -tx1 | tr -d ' \n')" != "7f454c46" ]; then
        continue
    fi

    # Never touch the loader — it is its own interpreter, and corrupting it
    # takes down every binary in the image at once.
    base=$(basename "$f")
    case "$base" in
        ld-*.so|ld-*.so.*|ld.so*) skipped=$((skipped + 1)); continue ;;
    esac

    if readelf -l "$f" 2>/dev/null | grep -q "Requesting program interpreter"; then
        if patchelf --set-interpreter "$LOADER" --set-rpath "$RPATH" "$f" 2>/dev/null; then
            patched_exec=$((patched_exec + 1))
        else
            skipped=$((skipped + 1))
        fi
    else
        # Shared library, or a static executable that needs nothing.
        if patchelf --set-rpath "$RPATH" "$f" 2>/dev/null; then
            patched_lib=$((patched_lib + 1))
        else
            skipped=$((skipped + 1))
        fi
    fi
done < <(find "$TREE" -type f -print0 2>/dev/null)

echo "  executables patched: $patched_exec"
echo "  libraries patched:   $patched_lib"
echo "  skipped:             $skipped"

if [ "$patched_exec" -lt 100 ]; then
    echo "ERROR: only $patched_exec executables patched — that is implausibly few" >&2
    exit 1
fi

echo "verification — bash should now request the on-device loader:"
readelf -l "$TREE/bin/bash" | grep -A1 "Requesting program interpreter" | sed 's/^/    /'
echo "  and its RPATH:"
patchelf --print-rpath "$TREE/bin/bash" | sed 's/^/    /'
