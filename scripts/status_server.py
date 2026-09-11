#!/data/data/com.termux/files/usr/bin/python
"""Authenticated, read-only local progress for the Android companion app."""
import hmac
import json
import os
from pathlib import Path
import re
import socket
import sys
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.error import HTTPError, URLError
from urllib.request import HTTPRedirectHandler, ProxyHandler, Request, build_opener

class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, request, response, code, message, headers, new_url):
        raise HTTPError(request.full_url, code, "Local service redirected", headers, response)


HTTP = build_opener(ProxyHandler({}), NoRedirect())


def read_json(url, token=None):
    headers = {"Authorization": "Bearer " + token} if token else {}
    with HTTP.open(Request(url, headers=headers), timeout=1.5) as response:
        if response.status != 200:
            raise ValueError("Unexpected response")
        return json.loads(response.read(65536))


def editor_ready(port):
    try:
        data = read_json(f"http://127.0.0.1:{int(port)}/healthz")
        return isinstance(data, dict) and data.get("status") in ("alive", "expired") and "lastHeartbeat" in data
    except (OSError, ValueError, URLError):
        return False


def port_open(port):
    try:
        with socket.create_connection(("127.0.0.1", int(port)), timeout=0.5):
            return True
    except OSError:
        return False


def pid_alive(pid):
    try:
        if int(pid) <= 0:
            return False
        os.kill(int(pid), 0)
        return True
    except (OSError, TypeError, ValueError):
        return False


def log_tail(path, token):
    try:
        with path.open("rb") as log:
            log.seek(0, 2)
            log.seek(max(0, log.tell() - 12000))
            text = log.read(12000).decode("utf-8", "replace")
        text = re.sub(r"\x1b\[[0-9;?]*[A-Za-z]", "", text)
        text = re.sub(r"[\x00-\x08\x0b\x0c\x0e-\x1f]", "", text)
        return text.replace(token, "[redacted]").splitlines()[-48:]
    except OSError:
        return []


def status_snapshot(directory, token):
    try:
        data = json.loads((directory / "status.json").read_text(encoding="utf-8"))
        if not isinstance(data, dict):
            raise ValueError("Invalid status")
    except (OSError, ValueError):
        data = {"schemaVersion": 1, "state": "installing", "stage": "checking", "progress": 0,
                "message": "Preparing installation.", "updatedAt": int(time.time())}
    # Child setup exits just before parent starts the editor; allow that handoff.
    if data.get("state") in ("installing", "starting") and time.time() - data.get("updatedAt", 0) > 8:
        if not pid_alive(data.get("operationPid")):
            data.update(state="error", message="Setup was interrupted. Tap Install or repair to resume safely.")
    if data.get("state") == "ready":
        try:
            port = int(data["editorUrl"].rsplit(":", 1)[1])
        except (KeyError, ValueError, IndexError):
            port = 8080
        if not editor_ready(port):
            data.update(state="error", stage="server", message="The editor has stopped. Tap Start editor to reconnect.")
    data["service"] = "openvscode-status"
    data["logTail"] = log_tail(directory / "install.log", token)
    if data.get("state") == "error" and data.get("stage") == "server":
        data["logTail"] += log_tail(directory / "server.log", token)[-20:]
    return data


def serve(directory, port):
    class Handler(BaseHTTPRequestHandler):
        def setup(self):
            super().setup()
            self.connection.settimeout(5)

        def do_GET(self):
            try:
                token = (directory / "status-token").read_text(encoding="ascii").strip()
            except (OSError, UnicodeError):
                token = ""
            provided = self.headers.get("Authorization", "")
            if not token or not hmac.compare_digest(provided.encode(), ("Bearer " + token).encode()):
                self.respond(401, {"error": "Authorization required"})
            elif self.path != "/status":
                self.respond(404, {"error": "Not found"})
            else:
                self.respond(200, status_snapshot(directory, token))

        def respond(self, code, data):
            body = json.dumps(data, ensure_ascii=False).encode("utf-8")
            self.send_response(code)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            self.send_header("X-Content-Type-Options", "nosniff")
            self.end_headers()
            try:
                self.wfile.write(body)
            except (BrokenPipeError, ConnectionResetError):
                pass

        def log_message(self, *_args):
            pass  # Never record authorization headers or poll noise.

    class Server(ThreadingHTTPServer):
        daemon_threads = True
        allow_reuse_address = True

    with Server(("127.0.0.1", int(port)), Handler) as server:
        server.serve_forever(poll_interval=0.5)


def main():
    command = sys.argv[1]
    if command == "--check-editor":
        return 0 if editor_ready(sys.argv[2]) else 1
    if command == "--port-open":
        return 0 if port_open(sys.argv[2]) else 1
    directory, port = Path(sys.argv[2]), int(sys.argv[3])
    if command == "--serve":
        serve(directory, port)
        return 0
    if command == "--check-status":
        try:
            token = (directory / "status-token").read_text().strip()
            result = read_json(f"http://127.0.0.1:{port}/status", token)
            return 0 if result.get("service") == "openvscode-status" else 1
        except (OSError, ValueError, URLError):
            return 1
    return 2


if __name__ == "__main__":
    sys.exit(main())
