#!/usr/bin/env bash
# ==============================================================================
# OpenVScode Mobile - Server Launcher with Wake-Lock Management
# ==============================================================================
set -euo pipefail

PORT="8080"
BIND_ADDR="127.0.0.1"
WORKSPACE="${HOME}/OpenVScode_Workspace"

# Parse arguments
while [[ $# -gt 0 ]]; do
    case "$1" in
        --port)
            PORT="$2"
            shift 2
            ;;
        --lan)
            BIND_ADDR="0.0.0.0"
            shift
            ;;
        --help|-h)
            echo "Usage: ./start.sh [--port <port>] [--lan]"
            echo "  --port <port>  Specify server port (default: 8080)"
            echo "  --lan          Listen on 0.0.0.0 so other devices on WiFi can connect"
            exit 0
            ;;
        *)
            shift
            ;;
    esac
done

mkdir -p "${WORKSPACE}"

# Acquire Termux wake lock if available to prevent CPU suspension
if command -v termux-wake-lock >/dev/null 2>&1; then
    echo ">> Acquiring Termux CPU wake lock..."
    termux-wake-lock
fi

# Cleanup handler on exit
cleanup() {
    echo ""
    echo ">> Stopping OpenVScode server..."
    if command -v termux-wake-unlock >/dev/null 2>&1; then
        echo ">> Releasing Termux CPU wake lock..."
        termux-wake-unlock || true
    fi
    exit 0
}
trap cleanup SIGINT SIGTERM EXIT

# Locate server executable
SERVER_BIN=""
if command -v code-server >/dev/null 2>&1; then
    SERVER_BIN="code-server"
elif command -v openvscode-server >/dev/null 2>&1; then
    SERVER_BIN="openvscode-server"
else
    echo ">> ERROR: Neither code-server nor openvscode-server was found in PATH."
    echo ">> Please run './setup.sh' first to install required components."
    exit 1
fi

echo "=================================================="
echo "   ⚡ OpenVScode Mobile IDE Server Starting"
echo "   Server URL: http://${BIND_ADDR}:${PORT}"
echo "   Workspace : ${WORKSPACE}"
echo "=================================================="

# Launch server
exec "${SERVER_BIN}" \
    --bind-addr "${BIND_ADDR}:${PORT}" \
    --auth none \
    "${WORKSPACE}"
