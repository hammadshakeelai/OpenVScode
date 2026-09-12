#!/data/data/com.termux/files/usr/bin/bash
# Explicit optional add-on: never upgrade Termux's package-managed pip.
set -Eeuo pipefail
command -v python >/dev/null || { printf 'Install the core environment first.\n' >&2; exit 1; }
printf 'Installing optional Python notebook kernel (can require native compilation).\n'
export PIP_DISABLE_PIP_VERSION_CHECK=1
export PIP_NO_INPUT=1
export PIP_DEFAULT_TIMEOUT=30
# ipykernel needs psutil, and psutil's build script rejects Android outright
# ("platform android is not supported"), so pip can never compile it here.
# Termux packages a patched build; installing it first leaves pip nothing to
# build. Non-fatal: if the package is unavailable, pip reports the real error.
if ! python -c 'import psutil' >/dev/null 2>&1; then
    printf 'Installing psutil from Termux packages; pip cannot build it on Android.\n'
    DEBIAN_FRONTEND=noninteractive timeout 600 apt-get install -y \
        -o DPkg::Lock::Timeout=120 -o Acquire::Retries=2 python-psutil \
        || printf 'Termux has no psutil package for this device; continuing.\n' >&2
fi
if ! timeout 900 python -m pip install --retries 2 ipykernel; then
    printf 'Notebook dependencies could not be installed. Python, C++ and the editor remain ready.\n' >&2
    exit 1
fi
python -m ipykernel install --user --name python3 --display-name 'Python 3 (Termux)'
# A kernelspec listing exits zero even when it lists nothing, so count instead
# of trusting the exit status: zero kernels is a failed notebook setup.
kernels="$(python -m jupyter kernelspec list 2>/dev/null | grep -cE '^[[:space:]]+[A-Za-z0-9._-]+[[:space:]]+/' || true)"
if [[ "${kernels:-0}" -lt 1 ]]; then
    printf 'No notebook kernel registered, so notebooks would not run. Python and C++ are unaffected.\n' >&2
    exit 1
fi
printf 'Registered %s notebook kernel(s).\n' "$kernels"
timeout 90 code-server --install-extension ms-toolsai.jupyter || {
    printf 'Add the Jupyter extension from the editor when your connection is available.\n' >&2
    exit 1
}
printf 'Python notebooks are ready. C++ programs run from the integrated terminal.\n'
