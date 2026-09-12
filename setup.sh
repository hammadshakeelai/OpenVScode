#!/data/data/com.termux/files/usr/bin/bash
# Install the essential offline development environment. Safe to retry.
set -Eeuo pipefail
RUNTIME_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
export RUNTIME_DIR
source "$RUNTIME_DIR/scripts/runtime.sh"
ov_parse_args "$@"
ov_init
ov_require_termux
ov_lock install
ov_stage installing packages 8 "Preparing Termux packages. Keep Termux running while downloads finish."
bash "$RUNTIME_DIR/scripts/install_toolchain.sh"
ov_stage installing editor 55 "Installing the editor from the Termux User Repository."
if ! command -v code-server >/dev/null 2>&1 || ! code-server --version >/dev/null 2>&1; then
    ov_apt install tur-repo
    ov_apt update
    ov_apt install code-server
fi
code-server --version || ov_fail "The editor package cannot start. Retry setup; if it persists, run pkg upgrade in Termux."
ov_stage installing workspace 78 "Preparing your workspace and mobile editor defaults."
mkdir -p "$OPENVSCODE_WORKSPACE"
# Add each missing example project on its own. Copying the tree only when no
# examples directory existed meant anyone who installed earlier never received
# examples added later — the notebook samples never arrived on such a device.
# A project the user already has is left exactly as it is, edits included.
if [[ -d "$RUNTIME_DIR/examples" ]]; then
    mkdir -p "$OPENVSCODE_WORKSPACE/examples"
    for project in "$RUNTIME_DIR/examples"/*; do
        [[ -e "$project" ]] || continue
        target="$OPENVSCODE_WORKSPACE/examples/$(basename -- "$project")"
        [[ -e "$target" ]] || cp -R -- "$project" "$target"
    done
fi
bash "$RUNTIME_DIR/scripts/install_extensions.sh" --settings-only
ov_stage installing extensions 85 "Adding Python and C++ editor support. These downloads are optional."
if ! bash "$RUNTIME_DIR/scripts/install_extensions.sh"; then
    ov_warn "Some editor extensions could not be downloaded. You can add them later from Extensions."
fi
if [[ "$OV_WITH_NOTEBOOKS" == 1 ]]; then
    ov_stage installing notebooks 90 "Adding optional notebook support. Python and C++ are already installed."
    if ! bash "$RUNTIME_DIR/scripts/install_jupyter_kernels.sh"; then
        ov_warn "Notebook setup did not finish. The editor, Python and C++ are still available."
    fi
fi
ov_stage installing complete 94 "Core installation complete. Starting the editor next."
ov_log "Setup complete. Start the editor with: bash \"$RUNTIME_DIR/start.sh\""
