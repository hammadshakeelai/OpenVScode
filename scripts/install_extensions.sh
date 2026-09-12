#!/data/data/com.termux/files/usr/bin/bash
# Optional extension downloads never block the core environment.
set -Eeuo pipefail
RUNTIME_DIR="${RUNTIME_DIR:-$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)}"
settings_dir="$HOME/.local/share/code-server/User"
mkdir -p "$settings_dir"
for name in settings.json keybindings.json; do
    if [[ ! -e "$settings_dir/$name" && -f "$RUNTIME_DIR/config/$name" ]]; then
        cp "$RUNTIME_DIR/config/$name" "$settings_dir/$name"
        printf 'Added mobile defaults: %s\n' "$name"
    fi
done
# An existing settings.json belongs to the user: add only the mobile defaults it
# has no opinion about, and never replace a value they chose. Without this, a
# default added in a later version never reaches anyone who already installed —
# which is how a workspace kept opening in Restricted Mode.
if [[ -f "$settings_dir/settings.json" && -f "$RUNTIME_DIR/config/settings.json" ]] \
    && command -v python >/dev/null 2>&1; then
    python - "$RUNTIME_DIR/config/settings.json" "$settings_dir/settings.json" <<'PY' \
        || printf 'Left your settings.json untouched; it is not plain JSON.\n'
import json
import os
import shutil
import sys

defaults_path, user_path = sys.argv[1], sys.argv[2]
with open(defaults_path, encoding="utf-8") as handle:
    defaults = json.load(handle)
with open(user_path, encoding="utf-8") as handle:
    user = json.load(handle)
missing = {key: value for key, value in defaults.items() if key not in user}
if not missing:
    raise SystemExit(0)
shutil.copy2(user_path, user_path + ".openvscode.bak")
user.update(missing)
temporary = user_path + ".openvscode.new"
with open(temporary, "w", encoding="utf-8") as handle:
    json.dump(user, handle, indent=2, ensure_ascii=False)
os.replace(temporary, user_path)
print("Added %d mobile default(s) your settings.json did not set" % len(missing))
PY
fi
[[ "${1:-}" != --settings-only ]] || exit 0
command -v code-server >/dev/null || exit 1
extensions=(ms-python.python llvm-vs-code-extensions.vscode-clangd)
installed="$(timeout 30 code-server --list-extensions 2>/dev/null || true)"
result=0
for extension in "${extensions[@]}"; do
    if grep -Fqx "$extension" <<< "$installed"; then continue; fi
    printf 'Adding optional editor extension: %s\n' "$extension"
    # The marketplace fails intermittently on a phone connection, and a lost
    # download here means no language server after an otherwise good install.
    # One retry converts most of those into a success.
    if attempt="$(timeout 90 code-server --install-extension "$extension" 2>&1)"; then
        printf '%s\n' "$attempt"
        continue
    fi
    printf '%s\n' "$attempt"
    # Some extensions ship no build this code-server can run. That is permanent:
    # retrying burns two minutes and "try again later" is misleading advice.
    if grep -qiE 'not available in code-server|is not compatible|no compatible version' <<< "$attempt"; then
        printf 'This code-server build cannot run %s, so editor support for it is unavailable. Compiling and running still work from the terminal.\n' "$extension" >&2
        continue
    fi
    printf 'Retrying %s once.\n' "$extension"
    if timeout 120 code-server --install-extension "$extension"; then continue; fi
    printf 'Could not add %s. Retry from the editor Extensions panel later.\n' "$extension" >&2
    result=1
done
exit "$result"
