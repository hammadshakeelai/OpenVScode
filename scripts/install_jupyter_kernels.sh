#!/usr/bin/env bash
# ==============================================================================
# OpenVScode Mobile - Dual-Kernel Jupyter Installer (Python 3 & C++ Clang)
# ==============================================================================
set -euo pipefail

echo "=================================================="
echo " [OpenVScode] Installing Jupyter Kernels (Python & C++)"
echo "=================================================="

PYTHON_BIN="python3"
if ! command -v python3 >/dev/null 2>&1 && command -v python >/dev/null 2>&1; then
    PYTHON_BIN="python"
fi

echo ">> Installing ipykernel and Jupyter base packages via ${PYTHON_BIN}..."
${PYTHON_BIN} -m pip install --no-cache-dir ipykernel jupyter_client || true

echo ">> Registering Python 3 kernel spec..."
${PYTHON_BIN} -m ipykernel install --user --name python3 --display-name "Python 3 (OpenVScode Mobile)" || true

echo ">> Installing C++ Jupyter kernel (jupyter-cpp-kernel)..."
${PYTHON_BIN} -m pip install --no-cache-dir jupyter-cpp-kernel || true

if command -v jupyter-cpp-kernel >/dev/null 2>&1; then
    echo ">> Registering C++ Clang kernel spec..."
    jupyter-cpp-kernel --install --user || true
fi

echo ">> Installed Jupyter kernels list:"
${PYTHON_BIN} -m jupyter_client.kernelspec list || true

echo ">> Jupyter kernel installation completed!"
