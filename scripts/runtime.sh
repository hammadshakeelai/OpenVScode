#!/data/data/com.termux/files/usr/bin/bash
# Shared shell primitives. Keep usable before Python is installed.

ov_parse_args() {
    OV_WITH_NOTEBOOKS=0
    OV_STATUS_TOKEN=""
    while (($#)); do
        case "$1" in
            --status-token)
                [[ $# -ge 2 && "$2" =~ ^[a-zA-Z0-9_-]{32,128}$ ]] || { printf 'Invalid status token.\n' >&2; exit 2; }
                OV_STATUS_TOKEN="$2"; shift 2 ;;
            --with-notebooks) OV_WITH_NOTEBOOKS=1; shift ;;
            --non-interactive) shift ;;
            *) printf 'Unknown option: %s\n' "$1" >&2; exit 2 ;;
        esac
    done
}

ov_init() {
    umask 077
    export OPENVSCODE_PORT="${OPENVSCODE_PORT:-8080}"
    export OPENVSCODE_STATUS_PORT="${OPENVSCODE_STATUS_PORT:-8766}"
    export OPENVSCODE_WORKSPACE="${OPENVSCODE_WORKSPACE:-$HOME/OpenVScode_Workspace}"
    export OV_STATE_DIR="${XDG_STATE_HOME:-$HOME/.local/state}/openvscode"
    for port in "$OPENVSCODE_PORT" "$OPENVSCODE_STATUS_PORT"; do
        [[ "$port" =~ ^[0-9]{1,5}$ ]] && ((10#$port > 1023 && 10#$port < 65536)) || { printf 'Invalid local port.\n' >&2; exit 2; }
    done
    mkdir -p "$OV_STATE_DIR"
    chmod 700 "$OV_STATE_DIR"
    if [[ -z "${OV_LOG_ACTIVE:-}" ]]; then
        if [[ -f "$OV_STATE_DIR/install.log" ]] && [[ $(wc -c < "$OV_STATE_DIR/install.log") -gt 2097152 ]]; then
            mv -f "$OV_STATE_DIR/install.log" "$OV_STATE_DIR/install.previous.log"
        fi
        export OV_LOG_ACTIVE=1
        exec > >(tee -a "$OV_STATE_DIR/install.log") 2>&1
    fi
    OV_STAGE="checking"
    OV_PROGRESS=0
    trap 'ov_on_exit $?' EXIT
    trap 'exit 130' INT
    trap 'exit 143' TERM
    if [[ -n "${OV_STATUS_TOKEN:-}" ]]; then
        printf '%s' "$OV_STATUS_TOKEN" > "$OV_STATE_DIR/status-token.tmp.$$"
        mv -f "$OV_STATE_DIR/status-token.tmp.$$" "$OV_STATE_DIR/status-token"
    elif [[ ! -s "$OV_STATE_DIR/status-token" ]]; then
        od -An -N32 -tx1 /dev/urandom | tr -d ' \n' > "$OV_STATE_DIR/status-token"
    fi
    unset OV_STATUS_TOKEN
}

ov_log() { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*"; }
ov_warn() { ov_log "Notice: $*" >&2; }
ov_escape() {
    local value="$1"
    value="${value//\\/\\\\}"; value="${value//\"/\\\"}"
    value="${value//$'\n'/\\n}"; value="${value//$'\r'/\\r}"; value="${value//$'\t'/\\t}"
    printf '%s' "$value"
}
ov_status() {
    local state="$1" message="$2" temporary="$OV_STATE_DIR/status.json.tmp.$$"
    printf '{"schemaVersion":1,"requestId":"%s","state":"%s","stage":"%s","message":"%s","progress":%s,"updatedAt":%s,"operationPid":%s,"editorUrl":"http://127.0.0.1:%s"}\n' \
        "$(ov_escape "${OPENVSCODE_REQUEST_ID:-}")" "$state" "$(ov_escape "$OV_STAGE")" "$(ov_escape "$message")" "$OV_PROGRESS" "$(date +%s)" "$$" "$OPENVSCODE_PORT" > "$temporary"
    mv -f "$temporary" "$OV_STATE_DIR/status.json"
}
ov_stage() {
    local state="$1"
    OV_STAGE="$2"; OV_PROGRESS="$3"
    ov_log "$4"
    ov_status "$state" "$4"
}
ov_fail() { ov_log "Error: $*" >&2; ov_status error "$*"; exit 1; }
ov_on_exit() {
    local code="$1"
    trap - EXIT
    if ((code != 0)) && [[ "${OV_SUPPRESS_FAILURE_STATUS:-0}" != 1 ]]; then
        if ! grep -q '"state":"error"' "$OV_STATE_DIR/status.json" 2>/dev/null; then
            ov_status error "Setup stopped during $OV_STAGE (exit $code). Check your connection and tap Install or repair to retry."
        fi
    fi
    # Closing the process releases its descriptor. Never explicitly unlock a
    # descriptor inherited from the parent: flock ownership is shared by both.
    exit "$code"
}
ov_require_termux() {
    [[ "${PREFIX:-}" == /data/data/com.termux/files/usr && -x "${PREFIX}/bin/pkg" ]] \
        || ov_fail "Run this installer inside Termux. Open Termux once, then return to the app."
    case "$(uname -m)" in
        aarch64|armv7l|armv8l|x86_64) ;;
        *) ov_fail "This device architecture is not supported by the code-server Termux package." ;;
    esac
}
ov_lock() {
    local lock="$OV_STATE_DIR/operation.lock"
    command -v flock >/dev/null || ov_fail "Termux is missing its file-lock utility. Run pkg install util-linux in Termux, then retry."
    # Both the APK staging shell and a parent installer may already own FD 9.
    # Verify its actual file before trusting the inherited marker.
    if [[ -n "${OV_INSTALL_OWNER:-}" && "$(readlink "/proc/$$/fd/9" 2>/dev/null || true)" == "$lock" ]] && flock -n 9; then
        return 0
    fi
    exec 9> "$lock"
    if ! flock -n 9; then
        ov_log "An OpenVScode operation is already running. Wait for it to finish."
        OV_SUPPRESS_FAILURE_STATUS=1
        exit 75
    fi
    export OV_INSTALL_OWNER="$$"
}
ov_check_space() {
    # Repairs of an installed core do not need space for another full copy.
    if command -v code-server >/dev/null && command -v python >/dev/null && command -v clang++ >/dev/null; then return 0; fi
    local available
    available="$(df -Pk "$HOME" | awk 'NR == 2 { print $4 }')"
    if [[ ! "$available" =~ ^[0-9]+$ ]]; then
        ov_warn "Could not measure free storage. Installation will report any storage error."
        return 0
    fi
    if ((available < 2097152)); then
        ov_fail "Only $((available / 1024)) MB is free in Termux storage. Initial setup needs at least 2048 MB. Free some device storage, then retry."
    fi
}
ov_apt() {
    local action="$1"; shift
    local attempt
    export DEBIAN_FRONTEND=noninteractive
    local -a options=(-y -o DPkg::Lock::Timeout=120 -o Acquire::Retries=2 -o Acquire::http::Timeout=30 -o Acquire::https::Timeout=30 -o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold)
    for attempt in 1 2 3; do
        if [[ "$action" == update ]]; then
            if apt-get "${options[@]}" update; then return 0; fi
        elif apt-get "${options[@]}" --no-remove "$action" "$@"; then return 0
        fi
        ov_warn "Package download attempt $attempt failed. Checking again shortly."
        ((attempt == 3)) || sleep $((attempt * 3))
    done
    ov_fail "Termux could not $action packages. Check your connection, close other package installs, and retry. If a mirror is unavailable, run termux-change-repo in Termux."
}
ov_editor_ready() {
    python "$RUNTIME_DIR/scripts/status_server.py" --check-editor "$OPENVSCODE_PORT" >/dev/null 2>&1
}
ov_status_start() {
    if python "$RUNTIME_DIR/scripts/status_server.py" --check-status "$OV_STATE_DIR" "$OPENVSCODE_STATUS_PORT" >/dev/null 2>&1; then return 0; fi
    nohup python "$RUNTIME_DIR/scripts/status_server.py" --serve "$OV_STATE_DIR" "$OPENVSCODE_STATUS_PORT" \
        > "$OV_STATE_DIR/status-server.log" 2>&1 < /dev/null 9>&- &
    local status_pid=$!
    printf '%s\n' "$status_pid" > "$OV_STATE_DIR/status-server.pid"
    for attempt in 1 2 3 4 5; do
        if python "$RUNTIME_DIR/scripts/status_server.py" --check-status "$OV_STATE_DIR" "$OPENVSCODE_STATUS_PORT" >/dev/null 2>&1; then return 0; fi
        kill -0 "$status_pid" 2>/dev/null || break
        sleep 1
    done
    ov_warn "Live progress is unavailable on port $OPENVSCODE_STATUS_PORT. Installation continues; details are in the Termux log."
    return 0
}
