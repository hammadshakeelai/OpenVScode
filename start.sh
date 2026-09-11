#!/data/data/com.termux/files/usr/bin/bash
# Start only after confirming that the editor really answers /healthz.
set -Eeuo pipefail
RUNTIME_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
export RUNTIME_DIR
source "$RUNTIME_DIR/scripts/runtime.sh"
ov_parse_args "$@"
ov_init
ov_require_termux
ov_lock install
command -v python >/dev/null 2>&1 || ov_fail "Python is missing. Choose Install or repair in the app."
command -v code-server >/dev/null 2>&1 || ov_fail "The editor is not installed. Choose Install or repair in the app."
ov_status_start
ov_stage starting server 96 "Starting your editor. The first launch can take a minute."
if ov_editor_ready; then
    ov_stage ready ready 100 "Your editor is ready."
    exit 0
fi
if python "$RUNTIME_DIR/scripts/status_server.py" --port-open "$OPENVSCODE_PORT"; then
    ov_fail "Port $OPENVSCODE_PORT is used by another service. Stop that service in Termux, then try Start again."
fi
mkdir -p "$OPENVSCODE_WORKSPACE" "$HOME/.config/openvscode"
# An app-specific config leaves the user's own code-server config untouched.
printf 'bind-addr: 127.0.0.1:%s\nauth: none\ncert: false\n' "$OPENVSCODE_PORT" > "$HOME/.config/openvscode/code-server.yaml"
termux-wake-lock >/dev/null 2>&1 || ov_warn "The wake lock is unavailable. Keep Termux active during long builds."
ov_log "Starting editor at http://127.0.0.1:$OPENVSCODE_PORT"
nohup code-server --config "$HOME/.config/openvscode/code-server.yaml" \
    --bind-addr "127.0.0.1:$OPENVSCODE_PORT" --auth none --disable-telemetry \
    "$OPENVSCODE_WORKSPACE" > "$OV_STATE_DIR/server.log" 2>&1 < /dev/null 9>&- &
server_pid=$!
printf '%s\n' "$server_pid" > "$OV_STATE_DIR/server.pid"
# Keep compatibility with the original documented stop command.
printf '%s\n' "$server_pid" > "$HOME/.openvscode.pid"
for ((attempt=0; attempt<90; attempt++)); do
    if ov_editor_ready; then
        ov_stage ready ready 100 "Your editor is ready."
        ov_log "Open http://127.0.0.1:$OPENVSCODE_PORT or return to the OpenVScode app."
        exit 0
    fi
    if ! kill -0 "$server_pid" 2>/dev/null; then
        tail -n 25 "$OV_STATE_DIR/server.log" >&2
        ov_fail "The editor exited during startup. Choose Install or repair, or inspect the server log in Diagnostics."
    fi
    sleep 1
done
tail -n 25 "$OV_STATE_DIR/server.log" >&2
ov_fail "The editor is taking longer than expected. Wait a moment and try Start again; server.log contains details."
