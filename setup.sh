#!/data/data/com.termux/files/usr/bin/bash
#
# OpenVScode Mobile — one-shot provisioner.
#
# Installs code-server, Python 3, C/C++ (Clang) and Jupyter inside Termux, then
# leaves an IDE listening on 127.0.0.1:8080 that the Android app connects to
# with no address to type and no network to configure.
#
# Designed to be driven unattended by the app (Termux RUN_COMMAND), so it never
# prompts, never fails the whole run for one optional package, and is safe to
# run again after an interruption.
set -u

VERSION="1.0"
LOG="$HOME/openvscode-setup.log"
REPO_DIR="${REPO_DIR:-$HOME/OpenVScode}"
export REPO_DIR

log()  { printf '\033[1;32m[setup]\033[0m %s\n' "$*" | tee -a "$LOG"; }
warn() { printf '\033[1;33m[setup]\033[0m %s\n' "$*" | tee -a "$LOG" >&2; }
step() { printf '\n\033[1;32m===> %s\033[0m\n' "$*" | tee -a "$LOG"; }

echo "OpenVScode Mobile setup v$VERSION — $(date)" >> "$LOG"

if [ ! -d "/data/data/com.termux" ]; then
    warn "This script is meant to run inside Termux."
    warn "Install Termux from F-Droid (NOT the Play Store build) and run it there."
    exit 1
fi

log "Logging everything to $LOG"
log "Architecture: $(uname -m)"

# Storage permission is what lets the IDE see your Downloads/Documents. It shows
# a one-time Android dialog; harmless and skippable if already granted.
if [ ! -d "$HOME/storage" ]; then
    step "Requesting storage access (one Android prompt)"
    termux-setup-storage 2>/dev/null || warn "termux-setup-storage unavailable; continuing without shared storage"
    sleep 2
fi

step "Step 1/5 — Language toolchains (Python, Clang, Node)"
if [ -f "$REPO_DIR/scripts/install_toolchain.sh" ]; then
    bash "$REPO_DIR/scripts/install_toolchain.sh" 2>&1 | tee -a "$LOG"
else
    warn "scripts/install_toolchain.sh missing — installing the essentials inline"
    pkg update -y >/dev/null 2>&1
    pkg install -y git curl python python-pip clang make cmake nodejs-lts >/dev/null 2>&1 \
        || warn "inline toolchain install had failures"
fi

step "Step 2/5 — code-server"
if command -v code-server >/dev/null 2>&1; then
    log "code-server already present ($(code-server --version 2>/dev/null | head -1))"
else
    # TUR ships prebuilt aarch64 binaries, which avoids a very long source build.
    log "Enabling the Termux User Repository…"
    if pkg install -y tur-repo >/dev/null 2>&1 && pkg update -y >/dev/null 2>&1 \
       && pkg install -y code-server >/dev/null 2>&1; then
        log "code-server installed from TUR."
    else
        warn "TUR install failed — falling back to npm (slower, compiles native deps)"
        if command -v npm >/dev/null 2>&1; then
            npm install -g code-server 2>&1 | tail -5 | tee -a "$LOG" \
                || warn "npm install of code-server failed"
        else
            warn "npm unavailable; cannot install code-server"
        fi
    fi
fi

if ! command -v code-server >/dev/null 2>&1; then
    warn "code-server could not be installed. See $LOG for details."
    warn "The app will keep showing its connect screen until this succeeds."
    exit 1
fi

step "Step 3/5 — Jupyter kernels (Python + C++)"
if [ -f "$REPO_DIR/scripts/install_jupyter_kernels.sh" ]; then
    bash "$REPO_DIR/scripts/install_jupyter_kernels.sh" 2>&1 | tee -a "$LOG"
else
    warn "scripts/install_jupyter_kernels.sh missing — skipping Jupyter"
fi

step "Step 4/5 — Editor configuration"
# Bind to loopback only and disable auth: nothing off-device can reach it, and
# the app should not have to prompt for a password it cannot know.
CONFIG_DIR="$HOME/.config/code-server"
mkdir -p "$CONFIG_DIR"
cat > "$CONFIG_DIR/config.yaml" <<'YAML'
bind-addr: 127.0.0.1:8080
auth: none
cert: false
YAML
log "code-server bound to 127.0.0.1:8080 (loopback only, no password)."

if [ -f "$REPO_DIR/scripts/install_extensions.sh" ]; then
    bash "$REPO_DIR/scripts/install_extensions.sh" 2>&1 | tee -a "$LOG"
fi

step "Step 5/5 — Workspace"
WORKSPACE="$HOME/OpenVScode_Workspace"
mkdir -p "$WORKSPACE"
if [ -d "$REPO_DIR/examples" ] && [ ! -e "$WORKSPACE/examples" ]; then
    cp -r "$REPO_DIR/examples" "$WORKSPACE/" 2>/dev/null && log "Copied example projects into the workspace."
fi
log "Workspace ready at $WORKSPACE"

step "Done"
log "Start the IDE with:  ./start.sh"
log "Then open the OpenVScode app — it finds 127.0.0.1:8080 on its own."
