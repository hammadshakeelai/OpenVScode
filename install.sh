#!/data/data/com.termux/files/usr/bin/bash
#
# OpenVScode Mobile — one-command installer for Termux.
#
# Run this and nothing else:
#
#   pkg install -y curl && curl -fsSL https://raw.githubusercontent.com/hammadshakeelai/OpenVScode/master/install.sh | bash
#
# It installs the toolchain, code-server, Python, C++ and the Jupyter kernels,
# then starts the IDE on 127.0.0.1:8080 where the Android app finds it.
#
# Safe to re-run. Every step checks whether it is already done, so an
# interrupted install is fixed by running the same command again.
set -u

REPO_URL="https://github.com/hammadshakeelai/OpenVScode.git"
REPO_DIR="$HOME/OpenVScode"
LOG="$HOME/openvscode-install.log"

# --- output helpers -------------------------------------------------------
# Every step says what it is about to do and roughly how long it takes, because
# the slow ones look identical to a hang otherwise.
bold=$(printf '\033[1m'); green=$(printf '\033[1;32m')
yellow=$(printf '\033[1;33m'); red=$(printf '\033[1;31m')
dim=$(printf '\033[2m'); off=$(printf '\033[0m')

step()  { printf '\n%s==> %s%s\n' "$green" "$*" "$off" | tee -a "$LOG"; }
info()  { printf '    %s\n' "$*" | tee -a "$LOG"; }
note()  { printf '    %s%s%s\n' "$dim" "$*" "$off" | tee -a "$LOG"; }
warn()  { printf '    %s! %s%s\n' "$yellow" "$*" "$off" | tee -a "$LOG" >&2; }
fail()  { printf '\n%sX %s%s\n' "$red" "$*" "$off" | tee -a "$LOG" >&2; exit 1; }

echo "OpenVScode Mobile installer — $(date)" >> "$LOG"

printf '\n%sOpenVScode Mobile%s\n' "$bold" "$off"
printf 'Installs a full VS Code IDE with Python, C++ and Jupyter on this phone.\n'
printf 'Everything is logged to %s\n' "$LOG"

# --- sanity ---------------------------------------------------------------
step "Checking this is Termux"
if [ ! -d /data/data/com.termux ]; then
    fail "This has to run inside Termux. Install Termux from F-Droid — the Play Store build is abandoned and will not work."
fi
info "Termux found. Architecture: $(uname -m)"

case "$(uname -m)" in
    aarch64) ;;
    *) warn "Architecture is $(uname -m), not aarch64. Most phones are aarch64; some packages may be unavailable." ;;
esac

# --- storage --------------------------------------------------------------
step "Granting access to your files (one Android prompt)"
if [ -d "$HOME/storage" ]; then
    note "Already granted — skipping."
else
    info "Tap Allow when Android asks. This lets the IDE open your Downloads and Documents."
    termux-setup-storage 2>/dev/null || warn "Could not request storage access; the IDE will still work on its own files."
    sleep 2
fi

# --- fetch ----------------------------------------------------------------
step "Fetching the installer scripts (about 30 seconds)"
info "Installing git and curl first."
pkg install -y git curl >>"$LOG" 2>&1 || fail "Could not install git. Check your internet connection and re-run."

if [ -d "$REPO_DIR/.git" ]; then
    info "Repository already here — updating it."
    git -C "$REPO_DIR" pull --quiet >>"$LOG" 2>&1 || warn "Could not update; continuing with what is already downloaded."
else
    info "Downloading from GitHub."
    git clone --depth 1 "$REPO_URL" "$REPO_DIR" >>"$LOG" 2>&1 \
        || fail "Could not download the repository. Check your internet connection and re-run."
fi
info "Scripts are in $REPO_DIR"

# --- the long part --------------------------------------------------------
step "Installing Python, C++, code-server and Jupyter"
info "This is the slow part. Expect 10-30 minutes depending on your phone and"
info "connection, and keep the screen on. Progress is written to the log as it goes."
note "If it stops partway, just run the same one-line command again — it resumes."

cd "$REPO_DIR" || fail "Could not enter $REPO_DIR"
if bash setup.sh 2>&1 | tee -a "$LOG"; then
    info "Toolchain installed."
else
    fail "Setup did not finish. The last lines of $LOG say why; re-running is safe."
fi

# --- start ----------------------------------------------------------------
step "Starting the IDE"
if bash start.sh 2>&1 | tee -a "$LOG"; then
    printf '\n%s%sDone.%s\n' "$bold" "$green" "$off"
    printf '\nThe IDE is running on %shttp://127.0.0.1:8080%s\n' "$bold" "$off"
    printf '\nNow either:\n'
    printf '  %s*%s open the %sOpenVScode%s app — it finds the IDE on its own, or\n' "$green" "$off" "$bold" "$off"
    printf '  %s*%s open http://127.0.0.1:8080 in Chrome.\n' "$green" "$off"
    printf '\nTo start it again later, run:  %scd ~/OpenVScode && ./start.sh%s\n' "$bold" "$off"
    printf 'To stop it:                    %skill $(cat ~/.openvscode.pid 2>/dev/null || echo "<pid>")%s\n\n' "$bold" "$off"
else
    fail "The IDE did not start. See $LOG — re-running this installer is safe."
fi
