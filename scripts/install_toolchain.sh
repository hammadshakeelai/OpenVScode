#!/data/data/com.termux/files/usr/bin/bash
set -Eeuo pipefail
RUNTIME_DIR="${RUNTIME_DIR:-$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)}"
export RUNTIME_DIR
source "$RUNTIME_DIR/scripts/runtime.sh"
ov_parse_args "$@"
ov_init
ov_require_termux
ov_lock install
ov_check_space
ov_stage installing packages 10 "Refreshing package information. Downloads resume automatically after a connection failure."
export DEBIAN_FRONTEND=noninteractive
# Recover interrupted package configuration without deleting dpkg lock files.
if ! dpkg --configure -a --force-confdef --force-confold; then
    ov_warn "Finishing an interrupted package operation."
    ov_apt install --fix-broken
fi
ov_apt update
# Python is installed first so the app receives live progress during big downloads.
ov_apt install python curl
python --version
ov_status_start
ov_stage installing toolchain 25 "Installing Python, Git and the C++ compiler. This may take several minutes."
ov_apt install python-pip git clang make cmake pkg-config
# code-server's package chooses its supported Node version; do not force nodejs-lts.
ov_stage installing verification 48 "Checking that Python runs and C++ programs compile on this device."
python -c 'import sys, ssl, sqlite3; print("Python", sys.version.split()[0], "is ready")'
for binary in git clang clang++ make cmake; do
    command -v "$binary" >/dev/null || ov_fail "Required tool $binary is missing. Retry Install or repair."
done
smoke_dir="$(mktemp -d "$OV_STATE_DIR/compiler-check.XXXXXX")"
printf '#include <iostream>\nint main(){std::cout << "C++ is ready" << std::endl;}\n' > "$smoke_dir/main.cpp"
clang++ "$smoke_dir/main.cpp" -o "$smoke_dir/check"
"$smoke_dir/check"
rm -f -- "$smoke_dir/main.cpp" "$smoke_dir/check"
rmdir -- "$smoke_dir"
ov_log "Python and C++ verification passed."
