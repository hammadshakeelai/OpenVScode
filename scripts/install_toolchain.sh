#!/data/data/com.termux/files/usr/bin/bash
#
# Installs the language toolchains: Python 3, C/C++ (Clang), Node, and the
# build tools code-server needs. Safe to re-run — every step is idempotent.
set -u

log() { printf '\033[1;34m[toolchain]\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[toolchain]\033[0m %s\n' "$*" >&2; }

# Termux's apt wants this to avoid interactive dpkg prompts on re-runs.
export DEBIAN_FRONTEND=noninteractive
APT_OPTS='-o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold'

log "Refreshing package lists…"
pkg update -y $APT_OPTS >/dev/null 2>&1 || warn "pkg update reported problems; continuing"

# Split into groups so one unavailable package cannot sink the whole install.
CORE="git curl wget openssh"
PYTHON="python python-pip"
NATIVE="clang make cmake ninja binutils pkg-config libllvm"
NODE="nodejs-lts"

for group in "$CORE" "$PYTHON" "$NATIVE" "$NODE"; do
    log "Installing: $group"
    # shellcheck disable=SC2086
    if ! pkg install -y $APT_OPTS $group >/dev/null 2>&1; then
        warn "Group failed as a batch, retrying one package at a time…"
        for p in $group; do
            pkg install -y $APT_OPTS "$p" >/dev/null 2>&1 \
                || warn "could not install '$p' — continuing without it"
        done
    fi
done

log "Verifying what actually landed:"
for bin in python3 pip clang clang++ make cmake node npm git; do
    if command -v "$bin" >/dev/null 2>&1; then
        printf '  \033[0;32mok\033[0m   %-9s %s\n' "$bin" "$(command -v "$bin")"
    else
        printf '  \033[0;31mMISS\033[0m %-9s not on PATH\n' "$bin"
    fi
done

# clangd powers autocomplete in the editor; it lives in a separate package and
# is genuinely optional, so a failure here must not fail the run.
if ! command -v clangd >/dev/null 2>&1; then
    log "Adding clangd for C++ IntelliSense (optional)…"
    pkg install -y $APT_OPTS clangd >/dev/null 2>&1 || warn "clangd unavailable; C++ autocomplete will be limited"
fi

log "Toolchain step complete."
