#!/usr/bin/env python3
"""Linux integration tests using real Bash/HTTP and mocked Termux packages.

Run: python3 scripts/test_runtime.py (or run the same command inside WSL).
No packages or user files are changed. Only the copied Termux guard is bypassed.
The APK/Termux emulator smoke test is a separate required device check.
"""
import importlib.util
import fcntl
import json
import os
from pathlib import Path
import shutil
import signal
import socket
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.error import HTTPError
from urllib.request import ProxyHandler, Request, build_opener

ROOT = Path(__file__).resolve().parent.parent
SPEC = importlib.util.spec_from_file_location("status_server", ROOT / "scripts/status_server.py")
STATUS = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(STATUS)
HTTP = build_opener(ProxyHandler({}))
TOKEN = "a" * 64


def unused_port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


class RuntimeTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="openvscode-test-")
        self.directory = Path(self.temporary.name)
        self.runtime = self.directory / "runtime"
        self.runtime.mkdir()
        for name in ("install.sh", "setup.sh", "start.sh"):
            shutil.copy2(ROOT / name, self.runtime / name)
        for name in ("scripts", "config", "examples"):
            shutil.copytree(ROOT / name, self.runtime / name, ignore=shutil.ignore_patterns("__pycache__"))
        common = self.runtime / "scripts/runtime.sh"
        common.write_text(common.read_text().replace("ov_require_termux() {", "ov_require_termux() {\n    return 0 # Test copy only"))
        self.user_home = self.directory / "home"
        self.user_home.mkdir()
        self.state = self.user_home / ".local/state/openvscode"
        self.fakebin = self.directory / "bin"
        self.fakebin.mkdir()
        self.env = os.environ.copy()
        for name in ("OV_INSTALL_OWNER", "OV_LOG_ACTIVE", "XDG_STATE_HOME", "OV_SUPPRESS_FAILURE_STATUS"):
            self.env.pop(name, None)
        self.env.update(HOME=str(self.user_home), PATH=f"{self.fakebin}:/usr/bin:/bin", MOCK_DIRECTORY=str(self.directory),
                        OPENVSCODE_PORT=str(unused_port()), OPENVSCODE_STATUS_PORT=str(unused_port()),
                        OPENVSCODE_REQUEST_ID="test-request-123")
        self.script("python", f'exec "{sys.executable}" "$@"')
        for name in ("dpkg", "git", "clang", "make", "cmake", "termux-wake-lock"):
            self.script(name, "exit 0")
        self.script("sleep", 'case "$1" in 3|6) exit 0;; *) exec /bin/sleep "$@";; esac')
        self.script("apt-get", '''
count=$(cat "$MOCK_DIRECTORY/apt-count" 2>/dev/null || echo 0)
count=$((count+1)); printf '%s' "$count" > "$MOCK_DIRECTORY/apt-count"
printf 'apt attempt %s %s\\n' "$count" "$*"
if ((count <= ${MOCK_APT_FAILURES:-0})); then exit 42; fi
exit 0
''')
        self.script("clang++", '''
[[ "${MOCK_COMPILER_FAIL:-0}" == 0 ]] || exit 12
while (($#)); do if [[ "$1" == -o ]]; then shift; output="$1"; fi; shift; done
printf '#!/bin/bash\\nprintf "C++ is ready\\\\n"\\n' > "$output"
chmod +x "$output"
''')
        self.script("code-server", '''
case "$1" in
--version) echo '4.test'; exit 0;;
--list-extensions) printf 'ms-python.python\\nllvm-vs-code-extensions.vscode-clangd\\n'; exit 0;;
--install-extension) exit "${MOCK_EXTENSION_FAIL:-0}";;
esac
[[ "${MOCK_EDITOR_FAIL:-0}" == 0 ]] || exit 19
exec python "$MOCK_DIRECTORY/editor.py" "$@"
''')
        (self.directory / "editor.py").write_text('''
import json, sys
from http.server import BaseHTTPRequestHandler, HTTPServer
port = int(sys.argv[sys.argv.index('--bind-addr')+1].rsplit(':',1)[1])
class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        data = json.dumps({'status':'alive','lastHeartbeat':123}).encode()
        self.send_response(200); self.end_headers(); self.wfile.write(data)
    def log_message(self,*args): pass
HTTPServer(('127.0.0.1',port), Handler).serve_forever()
''')

    def tearDown(self):
        for name in ("server.pid", "status-server.pid"):
            try:
                os.kill(int((self.state / name).read_text()), signal.SIGTERM)
            except (OSError, ValueError):
                pass
        self.temporary.cleanup()

    def script(self, name, body):
        path = self.fakebin / name
        path.write_text("#!/bin/bash\n" + body + "\n")
        path.chmod(0o755)

    def run_script(self, name="install.sh", *arguments, expected=0):
        result = subprocess.run(["bash", str(self.runtime / name), "--status-token", TOKEN, *arguments],
                                env=self.env, capture_output=True, text=True, timeout=35)
        self.assertEqual(result.returncode, expected, result.stdout + result.stderr)
        return result

    def snapshot(self):
        return json.loads((self.state / "status.json").read_text())

    def test_shell_syntax(self):
        for script in [*ROOT.glob("*.sh"), *ROOT.joinpath("scripts").glob("*.sh")]:
            subprocess.run(["bash", "-n", str(script)], check=True)

    def test_full_install_detaches_and_restart_keeps_same_server(self):
        self.run_script()
        self.assertEqual(self.snapshot()["state"], "ready")
        self.assertEqual(self.snapshot()["requestId"], "test-request-123")
        pid = (self.state / "server.pid").read_text()
        self.run_script("start.sh")
        self.assertEqual((self.state / "server.pid").read_text(), pid)
        self.assertFalse((self.state / "install.lock").exists())

    def test_failed_download_retries_then_succeeds(self):
        self.env["MOCK_APT_FAILURES"] = "2"
        result = self.run_script()
        self.assertIn("attempt 2 failed", result.stdout)
        self.assertEqual(self.snapshot()["state"], "ready")

    def test_download_failure_is_nonzero_and_repair_resumes(self):
        self.env["MOCK_APT_FAILURES"] = "999"
        self.run_script(expected=1)
        self.assertEqual(self.snapshot()["state"], "error")
        self.assertIn("connection", self.snapshot()["message"])
        self.env["MOCK_APT_FAILURES"] = "0"
        self.run_script()
        self.assertEqual(self.snapshot()["state"], "ready")

    def test_failed_compiler_cannot_report_success(self):
        self.env["MOCK_COMPILER_FAIL"] = "1"
        self.run_script(expected=12)
        self.assertEqual(self.snapshot()["state"], "error")
        self.assertFalse((self.state / "server.pid").exists())

    def test_failed_editor_cannot_report_success(self):
        self.env["MOCK_EDITOR_FAIL"] = "1"
        self.run_script(expected=1)
        self.assertEqual(self.snapshot()["state"], "error")
        self.assertIn("exited", self.snapshot()["message"])

    def test_existing_settings_and_workspace_are_preserved(self):
        settings = self.user_home / ".local/share/code-server/User"
        settings.mkdir(parents=True)
        (settings / "settings.json").write_text('{"editor.fontSize": 23}')
        (settings / "keybindings.json").write_text('[{"key":"alt+a"}]')
        config = self.user_home / ".config/code-server"
        config.mkdir(parents=True)
        (config / "config.yaml").write_text("auth: password\npassword: user-secret\n")
        examples = self.user_home / "OpenVScode_Workspace/examples"
        examples.mkdir(parents=True)
        (examples / "user.txt").write_text("keep me")
        self.run_script()
        self.assertEqual((settings / "settings.json").read_text(), '{"editor.fontSize": 23}')
        self.assertEqual((settings / "keybindings.json").read_text(), '[{"key":"alt+a"}]')
        self.assertIn("user-secret", (config / "config.yaml").read_text())
        self.assertEqual((examples / "user.txt").read_text(), "keep me")

    def test_optional_extensions_do_not_block_core(self):
        self.script("code-server", (self.fakebin / "code-server").read_text().split("\n", 1)[1].replace(
            "printf 'ms-python.python\\nllvm-vs-code-extensions.vscode-clangd\\n'", "printf ''"))
        self.env["MOCK_EXTENSION_FAIL"] = "1"
        result = self.run_script()
        self.assertIn("could not be downloaded", result.stdout)
        self.assertEqual(self.snapshot()["state"], "ready")

    def test_live_lock_prevents_duplicate_and_start_during_install(self):
        self.state.mkdir(parents=True)
        (self.state / "status.json").write_text('{"state":"installing","message":"existing install"}')
        with (self.state / "operation.lock").open("w") as lock:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            self.run_script(expected=75)
            self.run_script("start.sh", expected=75)
        self.assertEqual(self.snapshot()["message"], "existing install")

    def test_stale_lock_is_recovered(self):
        self.state.mkdir(parents=True)
        (self.state / "operation.lock").write_text("stale contents from killed process")
        # A reused or stale parent marker must not bypass the actual lock.
        self.env["OV_INSTALL_OWNER"] = str(os.getpid())
        self.run_script()
        self.assertEqual(self.snapshot()["state"], "ready")

    def test_low_storage_fails_before_install(self):
        self.script("df", "printf 'Filesystem 1024-blocks Used Available Capacity Mounted\\nmock 5000000 4500000 500000 90%% /\\n'")
        command = 'set -Eeuo pipefail; source "$RUNTIME_DIR/scripts/runtime.sh"; ov_parse_args; ov_init; function command() { return 1; }; ov_check_space'
        result = subprocess.run(["bash", "-c", command], capture_output=True, text=True, timeout=10,
                                env=dict(self.env, RUNTIME_DIR=str(self.runtime)))
        self.assertEqual(result.returncode, 1)
        self.assertIn("488 MB", self.snapshot()["message"])
        self.assertIn("2048 MB", self.snapshot()["message"])

    def test_other_http_listener_is_not_editor(self):
        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                self.send_response(200); self.end_headers(); self.wfile.write(b"unrelated service")
            def log_message(self, *_args):
                pass
        server = ThreadingHTTPServer(("127.0.0.1", int(self.env["OPENVSCODE_PORT"])), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            self.run_script("start.sh", expected=1)
            self.assertIn("another service", self.snapshot()["message"])
        finally:
            server.shutdown(); server.server_close(); thread.join()

    def test_status_requires_token_and_redacts_logs(self):
        self.run_script()
        with (self.state / "install.log").open("a") as log:
            log.write("sample token " + TOKEN + "\n")
        url = f"http://127.0.0.1:{self.env['OPENVSCODE_STATUS_PORT']}/status"
        with self.assertRaises(HTTPError) as error:
            HTTP.open(url, timeout=2)
        self.assertEqual(error.exception.code, 401)
        with HTTP.open(Request(url, headers={"Authorization": "Bearer " + TOKEN}), timeout=3) as response:
            data = response.read().decode()
        self.assertNotIn(TOKEN, data)
        self.assertIn("[redacted]", data)
        self.assertEqual(json.loads(data)["service"], "openvscode-status")

    def test_dead_operation_reports_interruption(self):
        self.state.mkdir(parents=True)
        (self.state / "status.json").write_text(json.dumps({"state":"installing", "operationPid":99999999,
                                                           "updatedAt":int(time.time())-30}))
        self.assertEqual(STATUS.status_snapshot(self.state, TOKEN)["state"], "error")

    def test_status_survives_token_rotation(self):
        self.run_script()
        new_token = "b" * 64
        (self.state / "status-token").write_text(new_token)
        data = STATUS.read_json(f"http://127.0.0.1:{self.env['OPENVSCODE_STATUS_PORT']}/status", new_token)
        self.assertEqual(data["state"], "ready")

    def test_local_probes_never_follow_redirects(self):
        received = []
        class Destination(BaseHTTPRequestHandler):
            def do_GET(self):
                received.append(self.headers.get("Authorization"))
                self.send_response(200); self.end_headers(); self.wfile.write(b'{}')
            def log_message(self, *_args):
                pass
        destination = ThreadingHTTPServer(("127.0.0.1", 0), Destination)
        class Redirect(BaseHTTPRequestHandler):
            def do_GET(self):
                self.send_response(302)
                self.send_header("Location", f"http://127.0.0.1:{destination.server_port}/")
                self.end_headers()
            def log_message(self, *_args):
                pass
        redirect = ThreadingHTTPServer(("127.0.0.1", 0), Redirect)
        threads = []
        for server in (destination, redirect):
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start(); threads.append(thread)
        try:
            with self.assertRaises(HTTPError):
                STATUS.read_json(f"http://127.0.0.1:{redirect.server_port}/status", TOKEN)
            self.assertEqual(received, [])
        finally:
            for server, thread in zip((destination, redirect), threads):
                server.shutdown(); server.server_close(); thread.join()

    def test_ready_snapshot_detects_stopped_editor(self):
        self.run_script()
        os.kill(int((self.state / "server.pid").read_text()), signal.SIGTERM)
        for _ in range(20):
            if not STATUS.port_open(self.env["OPENVSCODE_PORT"]):
                break
            time.sleep(0.05)
        data = STATUS.status_snapshot(self.state, TOKEN)
        self.assertEqual(data["state"], "error")
        self.assertEqual(data["stage"], "server")


if __name__ == "__main__":
    unittest.main(verbosity=2)
