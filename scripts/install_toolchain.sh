#!/usr/bin/env bash
# ==============================================================================
# OpenVScode Mobile - Toolchain Installer (Python 3 & Modern C++ Clang)
# ==============================================================================
set -euo pipefail

echo "=================================================="
echo " [OpenVScode] Installing Compiler & Language Toolchains"
echo "=================================================="

if command -v pkg >/dev/null 2>&1; then
    # Termux Environment
    echo ">> Detected Termux environment. Updating repositories..."
    pkg update -y || apt-get update -y
    
    echo ">> Installing Clang, Python 3, and build utilities..."
    pkg install -y \
        clang \
        make \
        cmake \
        ninja \
        python \
        python-pip \
        git \
        curl \
        tar \
        termux-tools
elif command -v apt-get >/dev/null 2>&1; then
    # Debian / Ubuntu / PRoot
    echo ">> Detected Debian/Ubuntu environment."
    apt-get update -y
    apt-get install -y \
        build-essential \
        clang \
        clangd \
        cmake \
        ninja-build \
        python3 \
        python3-pip \
        python3-venv \
        git \
        curl \
        tar
else
    echo ">> WARNING: Unknown package manager. Please ensure python3 and clang are installed."
fi

# Upgrade pip and install wheel
if command -v python3 >/dev/null 2>&1; then
    python3 -m pip install --upgrade pip setuptools wheel || true
elif command -v python >/dev/null 2>&1; then
    python -m pip install --upgrade pip setuptools wheel || true
fi

echo ">> Toolchain installation completed successfully!"
