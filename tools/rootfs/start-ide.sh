#!/bin/bash
#
# Entry point inside the rootfs. The Android app runs exactly this, so the app
# side needs to know one path and nothing about what lives in here.
set -u

PORT="${OPENVSCODE_PORT:-8080}"
WORKSPACE="${OPENVSCODE_WORKSPACE:-$HOME/workspace}"

mkdir -p "$WORKSPACE"

# Already answering? Then there is nothing to do and the app can just connect.
if command -v curl >/dev/null 2>&1 \
   && curl -s -o /dev/null --max-time 2 "http://127.0.0.1:$PORT"; then
    echo "IDE already running on 127.0.0.1:$PORT"
    exit 0
fi

echo "Starting code-server on 127.0.0.1:$PORT…"
exec code-server \
    --bind-addr "127.0.0.1:$PORT" \
    --auth none \
    --disable-telemetry \
    --disable-update-check \
    "$WORKSPACE"
