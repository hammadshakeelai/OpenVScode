#!/usr/bin/env bash
# ==============================================================================
# OpenVScode Mobile - VS Code Extensions & Settings Installer
# ==============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

echo "=================================================="
echo " [OpenVScode] Installing Extensions & Tuned Config"
echo "=================================================="

# Locate code-server or openvscode-server binary
SERVER_BIN=""
if command -v code-server >/dev/null 2>&1; then
    SERVER_BIN="code-server"
elif command -v openvscode-server >/dev/null 2>&1; then
    SERVER_BIN="openvscode-server"
fi

EXTENSIONS=(
    "ms-python.python"
    "llvm-vs-code-extensions.vscode-clangd"
    "ms-toolsai.jupyter"
    "formulahendry.code-runner"
)

if [ -n "${SERVER_BIN}" ]; then
    echo ">> Found server executable: ${SERVER_BIN}"
    for ext in "${EXTENSIONS[@]}"; do
        echo ">> Installing extension: ${ext}..."
        "${SERVER_BIN}" --install-extension "${ext}" || echo "Notice: extension ${ext} failed or already installed."
    done
else
    echo ">> Notice: code-server not found in PATH yet. Extensions will be installed on first start."
fi

# Determine VS Code / code-server config directory
CONFIG_DIRS=(
    "${HOME}/.local/share/code-server/User"
    "${HOME}/.openvscode-server/data/Machine"
    "${HOME}/.openvscode-server/data/User"
)

for target_dir in "${CONFIG_DIRS[@]}"; do
    mkdir -p "${target_dir}"
    if [ -f "${REPO_DIR}/config/settings.json" ]; then
        cp -f "${REPO_DIR}/config/settings.json" "${target_dir}/settings.json"
        echo ">> Copied mobile settings.json to ${target_dir}"
    fi
    if [ -f "${REPO_DIR}/config/keybindings.json" ]; then
        cp -f "${REPO_DIR}/config/keybindings.json" "${target_dir}/keybindings.json"
        echo ">> Copied mobile keybindings.json to ${target_dir}"
    fi
done

# Prepare default workspace directory
WORKSPACE_DIR="${HOME}/OpenVScode_Workspace"
mkdir -p "${WORKSPACE_DIR}"
if [ -d "${REPO_DIR}/examples" ]; then
    cp -rn "${REPO_DIR}/examples/"* "${WORKSPACE_DIR}/" || true
    echo ">> Deployed starter examples to ${WORKSPACE_DIR}"
fi

echo ">> Extensions and configuration setup completed!"
