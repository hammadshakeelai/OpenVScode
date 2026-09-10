#!/data/data/com.termux/files/usr/bin/bash
#
# Installs Jupyter with a Python kernel and a C++ kernel.
#
# On Android, wheels for the scientific stack usually do not exist, so pip has
# to build from source. That needs the toolchain step to have run first, and it
# is why this stage is the slow one.
set -u

log() { printf '\033[1;35m[jupyter]\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[jupyter]\033[0m %s\n' "$*" >&2; }

if ! command -v pip >/dev/null 2>&1; then
    warn "pip is missing — run scripts/install_toolchain.sh first. Skipping."
    exit 0
fi

# Build flags that let native extensions compile against Termux's headers.
export CFLAGS="${CFLAGS:-} -Wno-error=implicit-function-declaration"
export LDFLAGS="${LDFLAGS:-} -L${PREFIX:-/data/data/com.termux/files/usr}/lib"

log "Upgrading pip tooling…"
pip install --upgrade pip setuptools wheel >/dev/null 2>&1 || warn "pip self-upgrade failed; continuing"

log "Installing Jupyter + Python kernel (this is the slow part — several minutes)…"
if pip install --no-input jupyterlab notebook ipykernel >/dev/null 2>&1; then
    python3 -m ipykernel install --user --name python3 --display-name "Python 3 (Termux)" >/dev/null 2>&1 \
        && log "Python kernel registered."
else
    warn "JupyterLab install failed. Trying a minimal notebook + ipykernel install…"
    if pip install --no-input notebook ipykernel >/dev/null 2>&1; then
        python3 -m ipykernel install --user --name python3 --display-name "Python 3 (Termux)" >/dev/null 2>&1
        log "Minimal Jupyter installed."
    else
        warn "Could not install Jupyter. Python and C++ still work in the editor."
    fi
fi

log "Installing the C++ kernel…"
if pip install --no-input jupyter-cpp-kernel >/dev/null 2>&1; then
    python3 -m jupyter_cpp_kernel.install >/dev/null 2>&1 \
        && log "C++ kernel registered." \
        || warn "C++ kernel installed but did not register; select it manually if missing"
else
    warn "jupyter-cpp-kernel unavailable. C++ still compiles from the terminal with clang++."
fi

log "Kernels currently registered:"
jupyter kernelspec list 2>/dev/null | sed 's/^/  /' || warn "jupyter not on PATH; skipping kernel listing"

log "Jupyter step complete."
