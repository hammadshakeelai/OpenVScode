#!/data/data/com.termux/files/usr/bin/bash
# The APK bundles this installer; no GitHub checkout is required.
# Also supports curl -fsSL https://raw.githubusercontent.com/hammadshakeelai/OpenVScode/master/install.sh | bash
set -Eeuo pipefail
RUNTIME_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]:-.}")" && pwd)"
if [[ ! -f "$RUNTIME_DIR/scripts/runtime.sh" ]]; then
    # Standalone download mode never git-pulls into an existing checkout.
    [[ "${PREFIX:-}" == /data/data/com.termux/files/usr && -x "${PREFIX}/bin/pkg" ]] || {
        printf 'Open this installer inside Termux, then retry.\n' >&2; exit 1;
    }
    command -v curl >/dev/null || pkg install -y curl
    destination="$HOME/.local/share/openvscode"
    mkdir -p "$destination"
    staging="$(mktemp -d "$destination/download.XXXXXX")"
    trap 'printf "Download stopped. Re-run the installer to retry.\n" >&2' ERR
    curl --fail --location --proto '=https' --tlsv1.2 --retry 3 --connect-timeout 20 \
        https://codeload.github.com/hammadshakeelai/OpenVScode/tar.gz/refs/heads/master \
        -o "$staging/source.tar.gz"
    mkdir "$staging/runtime"
    tar -xzf "$staging/source.tar.gz" --strip-components=1 -C "$staging/runtime"
    [[ -f "$staging/runtime/scripts/runtime.sh" ]] || { printf 'Incomplete download; please retry.\n' >&2; exit 1; }
    # Retain the versioned runtime so interrupted updates cannot break a launch.
    exec bash "$staging/runtime/install.sh" "$@"
fi
export RUNTIME_DIR
source "$RUNTIME_DIR/scripts/runtime.sh"
ov_parse_args "$@"
ov_init
ov_require_termux
ov_lock install
ov_stage installing checking 3 "Checking your Termux environment. Your projects and settings will be kept."
ov_log "OpenVScode setup. Detailed log: $OV_STATE_DIR/install.log"
arguments=()
[[ "$OV_WITH_NOTEBOOKS" == 0 ]] || arguments+=(--with-notebooks)
bash "$RUNTIME_DIR/setup.sh" "${arguments[@]}"
bash "$RUNTIME_DIR/start.sh"
ov_log "All set. Return to the OpenVScode app to start coding."
