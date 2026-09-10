#!/data/data/com.termux/files/usr/bin/bash
#
# Starts code-server on 127.0.0.1:8080 and holds a wake lock so Android does not
# suspend it mid-compile. Idempotent: if a server is already up, it says so and
# exits rather than starting a second one.
set -u

PORT="${OPENVSCODE_PORT:-8080}"
WORKSPACE="${OPENVSCODE_WORKSPACE:-$HOME/OpenVScode_Workspace}"
LOG="$HOME/openvscode-server.log"
PIDFILE="$HOME/.openvscode.pid"

log()  { printf '\033[1;32m[start]\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[start]\033[0m %s\n' "$*" >&2; }

if ! command -v code-server >/dev/null 2>&1; then
    warn "code-server is not installed. Run ./setup.sh first."
    exit 1
fi

# Already listening? Then there is nothing to do — the app can just connect.
if command -v curl >/dev/null 2>&1 \
   && curl -s -o /dev/null --max-time 2 "http://127.0.0.1:$PORT"; then
    log "A server is already answering on 127.0.0.1:$PORT."
    log "Open the OpenVScode app — it will connect automatically."
    exit 0
fi

# Keeps the CPU alive so long builds are not killed when the screen turns off.
termux-wake-lock 2>/dev/null && log "Wake lock acquired." \
    || warn "termux-wake-lock unavailable (install termux-api) — long builds may be suspended"

mkdir -p "$WORKSPACE"

log "Starting code-server on 127.0.0.1:$PORT…"
nohup code-server \
    --bind-addr "127.0.0.1:$PORT" \
    --auth none \
    --disable-telemetry \
    "$WORKSPACE" >"$LOG" 2>&1 &

echo $! > "$PIDFILE"

# Wait for it to actually answer rather than claiming success immediately.
for i in $(seq 1 30); do
    if curl -s -o /dev/null --max-time 2 "http://127.0.0.1:$PORT"; then
        log "IDE is up on http://127.0.0.1:$PORT (pid $(cat "$PIDFILE"))"
        log "Open the OpenVScode app — it finds this automatically."
        log "Stop it later with:  kill \$(cat $PIDFILE) && termux-wake-unlock"
        exit 0
    fi
    sleep 1
done

warn "code-server did not come up within 30s. Last lines of $LOG:"
tail -20 "$LOG" >&2
exit 1
