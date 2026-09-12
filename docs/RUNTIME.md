# Termux runtime and installation contract

The Android APK carries the installer, helper scripts, defaults and examples. The
app stages those files under `~/.local/share/openvscode/runtime` inside Termux and
uses Termux's `RUN_COMMAND` service to run Bash. Installing packages needs an
internet connection. Once installed, the editor and local Python/C++ programs can
run offline.

## Commands

```sh
# Install or repair the essential editor, Python and C++ environment, then start.
bash ~/.local/share/openvscode/runtime/install.sh --status-token '<app-generated-token>'

# Restart an installed environment without downloading packages.
bash ~/.local/share/openvscode/runtime/start.sh --status-token '<app-generated-token>'

# Optional Python notebook kernel and Jupyter extension.
bash ~/.local/share/openvscode/runtime/install.sh --with-notebooks
```

The token must be 32–128 ASCII letters, digits, hyphens or underscores. The app
generates 64 hexadecimal characters and retains the token privately. Commands run
manually without `--status-token` reuse the stored token, or generate one if absent.
`--non-interactive` is accepted for compatibility; installation already avoids
interactive prompts. Shared Android storage access is optional and is requested
separately by the app rather than blocking installation.

## Link check

Before any download, the app sends a short check instead of trusting that setup was
done: a few `printf` lines reporting the request id, CPU, free space and whether an
editor is already installed. Termux must run it and echo `openvscode-bridge-ok`; a
zero exit code alone is not accepted as proof, because Termux reports success for
commands it declined to run. The check stages no files and answers in about a
second, so a missing permission or an unset `allow-external-apps` surfaces before
the user waits through a package installation.

The check also prints `tool <name>` for `python`, `clang++`, `clangd`, `code-server`
and `jupyter` when each is present, so diagnostics can state what Termux actually
has instead of inferring it from a setting. It is reachable at any time from
**Help & diagnostics → Check the Termux link**, not only during first setup.

`ov_lock` exits 75 when another OpenVScode operation already holds the installer
lock. That is a healthy run in progress, not a failure: the app adopts the running
operation's `requestId` from the status endpoint and follows its progress, rather
than reporting the installation the user is watching as broken.

The bridge needs a Termux build that ships `com.termux.app.RunCommandService` and
declares `com.termux.permission.RUN_COMMAND` — the F-Droid or GitHub build. The
Google Play build has neither, and the app detects this before requesting a
permission Android would refuse.

`install.sh` and `start.sh` return zero only after `/healthz` identifies a running
code-server. Both commands use the same operation lock, reject duplicate work with
exit 75. Kernel file locks release automatically after interruption or reboot.
Detached daemons close the lock descriptor. `setup.sh` alone only
installs the environment. It does not start the editor. Background editor/status
processes redirect all three standard streams so Termux's result callback can
finish after startup.

## App progress API

The authenticated, read-only endpoint is `GET http://127.0.0.1:8766/status` with
`Authorization: Bearer <token>`. It becomes available after Python is installed.
Before that, Termux command completion and the app's bootstrap state cover errors.
No routes list or read arbitrary files, execute commands or modify the environment.
The endpoint rejects missing/wrong tokens, does not enable CORS and is bound to
IPv4 loopback. Probes neither use proxy settings nor follow redirects.

```json
{
  "schemaVersion": 1,
  "requestId": "app-operation-uuid",
  "service": "openvscode-status",
  "state": "installing",
  "stage": "toolchain",
  "message": "Installing Python, Git and the C++ compiler.",
  "progress": 25,
  "updatedAt": 1789130000,
  "operationPid": 12345,
  "editorUrl": "http://127.0.0.1:8080",
  "logTail": ["Recent installation output"]
}
```

States are `installing`, `starting`, `ready` and `error`. Progress is a stage
estimate, not a downloaded-byte percentage. Set `OPENVSCODE_REQUEST_ID` to the
app's current operation UUID and match `requestId` before accepting a result;
timestamps alone cannot distinguish two requests in the same second. A dead
installation process becomes an interruption error after an eight-second grace
period. A saved `ready` status is checked against the editor on each status request
so it cannot conceal a stopped server.

State/log files live in `~/.local/state/openvscode/` (`XDG_STATE_HOME` is respected).
`status.json` is atomically replaced. `install.log` has one previous rotated copy;
`server.log` records the latest editor startup. The endpoint returns bounded recent
log lines and removes the status token. The state directory is private to Termux.
The app's bridge captures its own bounded command transcript separately.

## Installation behavior

- Essential dependencies use Termux's package manager with finite download retries,
  a package-lock timeout and noninteractive preservation of configuration files.
  Interrupted dpkg configuration is retried. Package locks are never forcibly
  removed, and automatic package removals are disabled.
- Initial installation checks for at least 2 GB of free Termux storage and reports
  both the required and available space before downloading packages.
- code-server comes from TUR, as described in the [official code-server Termux
  guide](https://coder.com/docs/code-server/termux). Its package selects a compatible
  Node runtime. There is no automatic npm/source-build fallback.
- Python must import its core SSL/SQLite modules, and a small C++ program must
  compile and execute before core setup is accepted.
- Extension downloads have time limits and may fail without blocking the editor.
  Notebook dependencies are opt-in and similarly cannot turn a working core
  environment into a failed installation. Termux's package-managed pip is never
  upgraded with pip itself.
- Existing user settings, keybindings, code-server configuration and example
  projects are retained. Mobile defaults and examples are copied only if absent.
  An existing `settings.json` additionally receives only the default keys it does
  not already set, backed up once as `settings.json.openvscode.bak`; a value the
  user chose is never replaced, and a file that is not plain JSON is left alone.
  Otherwise a default added in a later version (workspace trust, for instance)
  would never reach anyone who had already installed.
  The app launches with its own config at `~/.config/openvscode/code-server.yaml`.
- The editor listens on `127.0.0.1:8080` with authentication disabled, matching the
  companion's local connection. It is not exposed on Wi-Fi, although other apps on
  the same Android device can access loopback. Do not change its bind address to
  expose this unauthenticated instance to a network.

`OPENVSCODE_PORT`, `OPENVSCODE_STATUS_PORT` and `OPENVSCODE_WORKSPACE` override the
defaults for manual usage/testing. The Android companion uses ports 8080 and 8766.
The runtime checks for the standard `com.termux` package path and supports the TUR
package's ARM64, ARM and x86_64 targets; it does not claim support for i686.

## Validation

Run `python3 scripts/test_runtime.py` on Linux (including WSL). The tests execute
real Bash and local HTTP servers in temporary directories, mock package/compiler
commands, and bypass the Termux guard only in the copied fixture. They cover
failure propagation, retry recovery, settings retention, duplicate/stale locks,
server identification, detached streams, authenticated progress, stopped servers,
token rotation and redirect rejection. They do not substitute for actual Termux
package installation and Android WebView testing.

Termux's external-command setup and callbacks follow the [official RUN_COMMAND
documentation](https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent).
