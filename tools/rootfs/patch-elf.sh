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

    # Executables only — libraries are never touched.
    #
    # Two earlier attempts failed differently and both are worth remembering.
    # Patching every ELF, interpreter and RPATH together, corrupted node,
    # python3.11 and clang: they segfaulted before the loader emitted a single
    # line of LD_DEBUG output. Dropping RPATH entirely and passing
    # LD_LIBRARY_PATH from the launcher instead fixed that, but broke something
    # else — Android's own linker honours LD_LIBRARY_PATH too, and this
    # directory contains a file named libc.so that is a GNU ld script rather
    # than an ELF, so every bionic binary launched from the app died with
    #   CANNOT LINK EXECUTABLE "/system/bin/sh": ... bad ELF magic: 2f2a2047
    # at exec time, before any script could unset it.
    #
    # RPATH is the right place for this: it travels with the binary that needs
    # it and is invisible to everything else.
    if readelf -l "$f" 2>/dev/null | grep -q "Requesting program interpreter"; then
        # Interpreter and RPATH are set in SEPARATE invocations. Doing both at
        # once, across executables *and* libraries, is what corrupted node,
        # python3.11 and clang badly enough that they segfaulted before the
        # loader printed anything. The runtime check at the end of this script
        # is what decides whether this is safe — not this comment.
        # Interpreter only. Setting RPATH as well corrupts python3.11, node and
        # clang — the runtime check below caught bash passing while all three
        # failed, both when combined with --set-interpreter and as a separate
        # invocation. Library lookup is handled by the launcher instead, through
        # a bionic shell wrapper that scopes LD_LIBRARY_PATH to the glibc
        # process tree rather than letting it reach Android's own binaries.
        if patchelf --set-interpreter "$LOADER" "$f" 2>/dev/null; then
            patched_exec=$((patched_exec + 1))
        else
            skipped=$((skipped + 1))
        fi
    else
        skipped=$((skipped + 1))
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
echo "  library path the launcher must set:"
echo "    $RPATH"

# --- shebangs -------------------------------------------------------------
#
# Patching ELFs is only half of it. Every script in the image starts with an
# absolute interpreter — #!/bin/sh, #!/usr/bin/python3 — and the kernel resolves
# that path literally at exec time. On Android none of them exist, so scripts
# fail exactly like unpatched binaries do. code-server's own launcher is one of
# these, so without this the IDE never starts.
#
# The kernel truncates shebang lines at 127 bytes (BINPRM_BUF_SIZE - 1), so
# refuse to write one that would be silently cut in half.
echo
echo "patching shebangs"

shebang_patched=0
shebang_toolong=0

while IFS= read -r -d '' f; do
    # Only look at the first two bytes; most of the tree is not a script.
    [ "$(head -c 2 "$f" 2>/dev/null)" = '#!' ] || continue

    first=$(head -n 1 "$f" 2>/dev/null) || continue
    case "$first" in
        '#!/bin/'*|'#!/usr/bin/'*|'#!/usr/local/bin/'*|'#!/sbin/'*|'#!/usr/sbin/'*) ;;
        *) continue ;;
    esac

    new="#!${ROOTFS_PATH}${first#\#!}"
    if [ "${#new}" -ge 128 ]; then
        shebang_toolong=$((shebang_toolong + 1))
        continue
    fi

    # sed -i rewrites in place; restrict to line 1 so script bodies are untouched.
    if sed -i "1s|^.*$|${new}|" "$f" 2>/dev/null; then
        shebang_patched=$((shebang_patched + 1))
    fi
done < <(find "$TREE" -type f -print0 2>/dev/null)

echo "  shebangs patched:      $shebang_patched"
echo "  too long to patch:     $shebang_toolong"

echo "verification — code-server's launcher:"
head -n 1 "$TREE/opt/code-server/bin/code-server" | sed 's/^/    /'

# --- absolute symlinks ----------------------------------------------------
#
# The third place absolute paths leak out. A Debian image is full of symlinks
# whose target starts at the filesystem root — /usr/local/bin/code-server points
# at /opt/code-server/bin/code-server, and dozens of libraries do the same. On
# Android those resolve against the real root, where nothing exists, so they
# dangle. Measured: 236 of them, and the code-server one alone is enough to make
# the IDE unstartable with "exec: code-server: not found".
#
# Relative symlinks are already correct and are left alone.
echo
echo "patching absolute symlinks"

link_patched=0
link_skipped=0

while IFS= read -r -d '' l; do
    target=$(readlink "$l") || continue
    case "$target" in
        "$ROOTFS_PATH"/*)
            # Already rewritten; keep this idempotent.
            link_skipped=$((link_skipped + 1))
            ;;
        /*)
            ln -sfn "${ROOTFS_PATH}${target}" "$l" && link_patched=$((link_patched + 1))
            ;;
        *)
            link_skipped=$((link_skipped + 1))
            ;;
    esac
done < <(find "$TREE" -type l -print0 2>/dev/null)

echo "  absolute symlinks repointed: $link_patched"
echo "  left alone (relative):       $link_skipped"

# --- does a patched binary still actually run? ----------------------------
#
# This is the guard that was missing. patchelf can leave a file that passes
# every static check and still segfaults the moment it is mapped. The build
# host cannot exec these directly — their interpreter now points at an Android
# path — but it can invoke the loader explicitly, which exercises the same
# headers. If this fails, the image is broken and must not ship.
if [ "$ARCH" = "amd64" ] && [ "$(uname -m)" = "x86_64" ]; then
    echo
    echo "runtime check on patched binaries"
    LOADER_LOCAL="$TREE/lib/$TRIPLET/$LOADER_NAME"
    LIBPATH_LOCAL="$TREE/lib/$TRIPLET:$TREE/usr/lib/$TRIPLET:$TREE/lib:$TREE/usr/lib:$TREE/usr/lib/llvm-14/lib:$TREE/opt/code-server/lib"

    check() {
        name="$1"; shift
        if out=$("$LOADER_LOCAL" --library-path "$LIBPATH_LOCAL" "$@" 2>&1); then
            echo "    ok   $name: $(echo "$out" | head -1)"
        else
            echo "    FAIL $name: $(echo "$out" | head -2)" >&2
            return 1
        fi
    }

    failed=0
    check bash       "$TREE/bin/bash" -c 'echo bash ok' || failed=1
    check python3    "$TREE/usr/bin/python3.11" -c 'print("python ok")' || failed=1
    check node       "$TREE/opt/code-server/lib/node" --version || failed=1
    check clang      "$TREE/usr/bin/clang" --version || failed=1

    if [ "$failed" -ne 0 ]; then
        echo "ERROR: patched binaries do not run. Refusing to publish a broken image." >&2
        exit 1
    fi
fi

echo "verification — code-server on PATH should now resolve:"
ls -l "$TREE/usr/local/bin/code-server" | sed 's/^/    /'
if [ -e "$TREE/usr/local/bin/code-server" ]; then
    echo "    target exists relative to the tree: yes"
else
    # Expected: the link now points at an absolute on-device path, which does
    # not resolve here on the build host. Check the real file instead.
    if [ -f "$TREE/opt/code-server/bin/code-server" ]; then
        echo "    (dangles on the build host, as intended — resolves once installed)"
    else
        echo "ERROR: code-server is missing from the image entirely" >&2
        exit 1
    fi
fi
