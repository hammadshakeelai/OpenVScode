#!/data/data/com.termux/files/usr/bin/bash
#
# Installs editor extensions and drops in the mobile-tuned settings from config/.
# code-server pulls from Open VSX, so ms-* Marketplace-only builds are not
# available; the equivalents below are the Open VSX ones that do work.
set -u

log() { printf '\033[1;36m[extensions]\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[extensions]\033[0m %s\n' "$*" >&2; }

if ! command -v code-server >/dev/null 2>&1; then
    warn "code-server not installed yet — skipping extensions."
    exit 0
fi

EXTENSIONS="
ms-python.python
ms-toolsai.jupyter
llvm-vs-code-extensions.vscode-clangd
"

for ext in $EXTENSIONS; do
    [ -z "$ext" ] && continue
    log "Installing $ext…"
    code-server --install-extension "$ext" >/dev/null 2>&1 \
        && log "  ok: $ext" \
        || warn "  could not install $ext (may not be on Open VSX) — skipping"
done

# --- mobile-tuned editor settings -----------------------------------------
REPO_DIR="${REPO_DIR:-$HOME/OpenVScode}"
SETTINGS_DIR="$HOME/.local/share/code-server/User"
mkdir -p "$SETTINGS_DIR"

if [ -f "$REPO_DIR/config/settings.json" ]; then
    if [ -f "$SETTINGS_DIR/settings.json" ]; then
        cp "$SETTINGS_DIR/settings.json" "$SETTINGS_DIR/settings.json.bak" 2>/dev/null
        log "Existing settings backed up to settings.json.bak"
    fi
    cp "$REPO_DIR/config/settings.json" "$SETTINGS_DIR/settings.json" \
        && log "Applied mobile editor settings."
else
    warn "config/settings.json not found under $REPO_DIR — leaving editor defaults"
fi

if [ -f "$REPO_DIR/config/keybindings.json" ]; then
    cp "$REPO_DIR/config/keybindings.json" "$SETTINGS_DIR/keybindings.json" \
        && log "Applied mobile keybindings."
fi

log "Extensions step complete."
