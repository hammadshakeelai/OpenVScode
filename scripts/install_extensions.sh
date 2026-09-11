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
[[ "${1:-}" != --settings-only ]] || exit 0
command -v code-server >/dev/null || exit 1
extensions=(ms-python.python llvm-vs-code-extensions.vscode-clangd)
installed="$(timeout 30 code-server --list-extensions 2>/dev/null || true)"
result=0
for extension in "${extensions[@]}"; do
    if grep -Fqx "$extension" <<< "$installed"; then continue; fi
    printf 'Adding optional editor extension: %s\n' "$extension"
    if ! timeout 90 code-server --install-extension "$extension"; then
        printf 'Could not add %s. Retry from the editor Extensions panel later.\n' "$extension" >&2
        result=1
    fi
done
exit "$result"
