#!/usr/bin/env bash
# ==============================================================================
# OpenVScode Mobile - 1-Click Bootstrap Installer
# ==============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "=================================================="
echo "   🚀 Starting OpenVScode Mobile Bootstrap Setup"
echo "=================================================="

# Check if running in Termux
IS_TERMUX=false
if [ -d "/data/data/com.termux" ]; then
    IS_TERMUX=true
fi

echo ">> Environment: $(uname -m) $(uname -s) (Termux: ${IS_TERMUX})"

# 1. Install Node.js & core packages
if [ "${IS_TERMUX}" = true ]; then
    echo ">> Updating Termux repositories and installing nodejs, git, curl..."
    pkg update -y || apt-get update -y
    pkg install -y nodejs-lts git curl tar
    
    # Try installing code-server via tur-repo (official fast Termux package)
    if ! command -v code-server >/dev/null 2>&1; then
        echo ">> Attempting code-server install via Termux User Repository (TUR)..."
        pkg install -y tur-repo || true
        pkg install -y code-server || true
    fi
else
    if command -v apt-get >/dev/null 2>&1; then
        apt-get update -y
        apt-get install -y nodejs npm git curl tar
    fi
fi

# Fallback npm global install if code-server is still not present
if ! command -v code-server >/dev/null 2>&1 && ! command -v openvscode-server >/dev/null 2>&1; then
    if command -v npm >/dev/null 2>&1; then
        echo ">> Installing code-server via npm..."
        npm install -g code-server --unsafe-perm || true
    fi
fi

# 2. Run Toolchain installer (Python 3, Clang 20, Make, Cmake)
chmod +x "${SCRIPT_DIR}/scripts/"*.sh
bash "${SCRIPT_DIR}/scripts/install_toolchain.sh"

# 3. Run Jupyter Kernel installer (Python & C++)
bash "${SCRIPT_DIR}/scripts/install_jupyter_kernels.sh"

# 4. Run Extensions and Settings installer
bash "${SCRIPT_DIR}/scripts/install_extensions.sh"

# 5. Make start.sh executable
chmod +x "${SCRIPT_DIR}/start.sh"

echo "=================================================="
echo "   ✅ OpenVScode Mobile Setup Complete!"
echo "   Launch your IDE anytime by running:"
echo "       ./start.sh"
echo "=================================================="
