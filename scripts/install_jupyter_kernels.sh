#!/data/data/com.termux/files/usr/bin/bash
# Explicit optional add-on: never upgrade Termux's package-managed pip.
set -Eeuo pipefail
command -v python >/dev/null || { printf 'Install the core environment first.\n' >&2; exit 1; }
printf 'Installing optional Python notebook kernel (can require native compilation).\n'
export PIP_DISABLE_PIP_VERSION_CHECK=1
export PIP_NO_INPUT=1
export PIP_DEFAULT_TIMEOUT=30
if ! timeout 900 python -m pip install --retries 2 ipykernel; then
    printf 'Notebook dependencies could not be installed. Core Python/C++ tools remain ready.\n' >&2
    exit 1
fi
python -m ipykernel install --user --name python3 --display-name 'Python 3 (Termux)'
timeout 90 code-server --install-extension ms-toolsai.jupyter || {
    printf 'Add the Jupyter extension from the editor when your connection is available.\n' >&2
    exit 1
}
printf 'Python notebooks are ready. C++ programs run from the integrated terminal.\n'
