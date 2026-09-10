<p align="center">
  <img src="docs/assets/banner.svg" alt="OpenVScode Mobile IDE — a full VS Code environment that runs on your phone, with Python 3, C++ (Clang) and Jupyter kernels" width="100%">
</p>

<p align="center">
  <a href="https://github.com/hammadshakeelai/OpenVScode/releases/latest/download/OpenVScode-Mobile-apk.zip">
    <img src="https://img.shields.io/badge/%E2%AC%87%20Download%20APK-Android%208.0%2B-007acc?style=for-the-badge&logo=android&logoColor=white" alt="Download the APK">
  </a>
</p>

<p align="center">
  <a href="https://github.com/hammadshakeelai/OpenVScode/actions/workflows/android.yml"><img src="https://github.com/hammadshakeelai/OpenVScode/actions/workflows/android.yml/badge.svg" alt="Build status"></a>
  <a href="https://github.com/hammadshakeelai/OpenVScode/releases/latest"><img src="https://img.shields.io/github/v/release/hammadshakeelai/OpenVScode?color=007acc" alt="Latest release"></a>
  <img src="https://img.shields.io/badge/platform-Android%20%7C%20Linux%20ARM64-green.svg" alt="Platform">
  <img src="https://img.shields.io/badge/license-MIT-blue.svg" alt="MIT license">
  <img src="https://img.shields.io/badge/python-3.11+-yellow.svg" alt="Python 3.11+">
  <img src="https://img.shields.io/badge/c++-Clang%2020-red.svg" alt="Clang 20">
  <img src="https://img.shields.io/badge/jupyter-Python%20%26%20C%2B%2B%20Kernels-orange.svg" alt="Jupyter kernels">
</p>

---

## 📲 Install the APK (straight from your phone)

> [!IMPORTANT]
> **Read this before you tap.** The APK is the Android *shell* — a WebView, a
> foreground service and the touch keybar. It does not contain an IDE; it connects
> to one. There are three ways to give it something to connect to, and only the
> first is fully proven end to end:
>
> 1. **Point it at a server you already run.** Type any address — a laptop on the
>    same Wi‑Fi, a hostname, a public URL — or let it scan your network. This works
>    today.
> 2. **Set it up through Termux** (one tap, needs Termux installed). The scripts are
>    written and the app drives them itself, but they have **not yet been run on a
>    real device**.
> 3. **Install the IDE image directly** (no Termux). Downloads and unpacks a full
>    Linux environment with Python, C++ and Jupyter — but **cannot launch it yet**.
>    The piece that runs binaries out of that image is still being built. Tapping
>    this today gets you a correctly-installed image and no IDE.

**On the phone, tap this link:**

### → [**Download OpenVScode-Mobile-apk.zip**](https://github.com/hammadshakeelai/OpenVScode/releases/latest/download/OpenVScode-Mobile-apk.zip) ←

That link always resolves to the newest build, so it never goes stale. Then:

1. Open **Files → Downloads** and tap the zip to extract it.
2. Tap `OpenVScode-Mobile.apk` inside.
3. Android will ask to allow installs from this source — tap **Settings**, turn on
   **Allow from this source**, go back, and tap **Install**.
4. Launch **OpenVScode** from your app drawer.

> [!TIP]
> **Why a zip and not the APK directly?** Chrome on Android routes `.apk` downloads
> through an APK-specific Safe Browsing check and holds the file until a verdict comes
> back. A debug-signed build from a small repo has no download reputation, so that
> verdict never resolves and the download **hangs at ~100% with no error** — the bytes
> have all arrived, Chrome just will not release the file. GitHub serves `.zip` as
> `application/octet-stream`, which skips that path. Same bytes either way; the
> checksums on each release prove it.

<details>
<summary><b>Other ways to get the APK</b></summary>

- **Direct APK:** [OpenVScode-Mobile.apk](https://github.com/hammadshakeelai/OpenVScode/releases/latest/download/OpenVScode-Mobile.apk)
  — identical build. Fine in Firefox, Samsung Internet, or on a desktop; this is the
  one that stalls in Chrome for Android.
- **Verify what you downloaded:** each release ships `SHA256SUMS.txt` covering both
  files. CI also fails the build if the zip does not extract to a byte-identical APK.
- **Browse every version:** the [Releases page](https://github.com/hammadshakeelai/OpenVScode/releases)
  lists each build with its notes.
- **Bleeding edge:** every push to `master` uploads both files to its
  [Actions run](https://github.com/hammadshakeelai/OpenVScode/actions/workflows/android.yml)
  as build artifacts. GitHub requires you to be signed in to download those, so the
  release link above is the one that works with a single tap on a phone.
- **Build it yourself:** `cd android && ./gradlew assembleDebug` — the APK lands in
  `android/app/build/outputs/apk/debug/`.

</details>

> [!NOTE]
> These are **debug** builds, signed with the standard Android debug key. That is what
> makes them installable without a Play Store account. Because `app/build.gradle` sets
> `applicationIdSuffix = ".debug"`, the package id is `com.openvscode.mobile.debug`, so
> it installs *alongside* a release build rather than replacing one.

### What the app does

The APK is a native Android shell around the IDE. It runs a **foreground service**
holding a wake lock, so Android will not kill your compiler mid-build; it renders the
editor in a hardware-accelerated **WebView**; and it overlays a **touch coding keybar**
(`ESC`, `TAB`, `CTRL`, `ALT`, `{ }`, `( )`, arrows) that a phone keyboard does not give you.

It expects the IDE server described below to be reachable on `127.0.0.1:8080`.

---

## ⚡ Alternative: run it under Termux

> [!NOTE]
> **These scripts are written but untested on a device.** `setup.sh`, `start.sh` and
> everything under `scripts/` now install the toolchain, code-server, and the Jupyter
> kernels, and every step is idempotent so a failed run can simply be repeated. They
> were developed on Windows against a Termux/aarch64 target with no way to run them,
> so treat the first run as the real test and please
> [report what breaks](https://github.com/hammadshakeelai/OpenVScode/issues).
>
> You can also skip the commands entirely: install the APK, and tap
> **Set up Python, C++ & Jupyter automatically** — the app runs all of this for you
> through Termux.

### Step 1: Install Termux
Get **Termux** from [F-Droid](https://f-droid.org/en/packages/com.termux/). The Google
Play build is deprecated and will not work.

### Step 2: Clone and run the installer
```bash
pkg update && pkg install -y git
git clone https://github.com/hammadshakeelai/OpenVScode.git
cd OpenVScode
bash setup.sh
```

### Step 3: Launch
```bash
./start.sh
```
Then open `http://127.0.0.1:8080` in your browser.

> [!TIP]
> **No APK needed for a fullscreen app:** in Chrome/Edge/Brave, tap ⋮ →
> **Add to Home screen** / **Install app**. It launches borderless, with no address
> bar, just like a native app.

---

## 🚀 Features & Built-in Capabilities

### 1. 🐍 Python Environment
- **Python 3 + Pip**: Full package management.
- **`ipykernel`**: Seamless integration with Jupyter.
- **Language Server**: Autocomplete, type hinting, and auto-imports.
- **1-Click Execution**: Dedicated Run button for Python scripts.

### 2. ⚡ C / C++ Environment (Clang & Clangd)
- **Modern C++20 Compiler**: `clang`, `clang++`, `make`, `cmake`, `ninja`.
- **IntelliSense with `clangd`**: Instant syntax checking, definition jump, and refactoring on ARM64.
- **Debugging**: `lldb` / `gdb` integration.
- **Clangd Config**: Pre-configured `compile_flags.txt` tuned for mobile CPU constraints (`-j=2`).

### 3. 📓 Jupyter Notebooks (Dual-Kernel: Python & C++)
- **Native Notebook UI**: Interactive cells directly inside VS Code (`ms-toolsai.jupyter`).
- **Python Kernel**: Standard `ipykernel`.
- **C++ Kernel (`jupyter-cpp-kernel`)**: **Just as easy as Python!** Installed via `pip`, it compiles and runs C++ code cells on the fly with `clang++`.
- Optional: Support for `xeus-cpp` (Clang-REPL) in PRoot for persistent cell variables.

### 4. 📱 Mobile-First Ergonomics
- **No Wasted Screen Space**: Minimap and glyph margins disabled; word-wrap enabled.
- **Background Protection**: Auto-save enabled on delay (`files.autoSave: "afterDelay"`) so no edits are lost if Android suspends the app.
- **Touch Coding Keybar**: Included `mobile-keyboard-bar.js` and Termux extra keys configuration providing `ESC`, `TAB`, `CTRL`, `ALT`, `{ }`, `( )`, and arrow keys.

---

## 📂 Repository Structure

```
OpenVScode/
├── setup.sh                         # 1-click bootstrap installer (Termux)
├── start.sh                         # Launcher with wake-lock, waits for the port
├── config/
│   ├── settings.json                # Pre-tuned mobile VS Code settings
│   ├── keybindings.json             # Mobile touch shortcuts
│   └── compile_flags.txt            # Clangd include paths & C++20 standard
├── scripts/                         # Toolchain / kernel / extension installers
├── examples/
│   ├── hello_python/                # Sample Python project
│   ├── hello_cpp/                   # Sample C++ project with Makefile
│   └── notebooks/                   # Sample Python & C++ Jupyter notebooks
├── android/                         # Native Android app (Gradle project)
│   ├── app/src/main/java/…          # MainActivity (WebView) + VScodeService (foreground service)
│   ├── app/src/main/res/…           # Layouts, theme, launcher icon
│   ├── manifest.json                # PWA manifest for standalone browser mode
│   └── mobile-keyboard-bar.js       # On-screen touch coding keyboard bar
├── test-harness/                    # Browser simulator for the keybar: phone viewport,
│                                    # virtual-keyboard resize, key injection, test suite
├── tools/rootfs/                    # Dockerfile for the downloadable Linux image
├── .github/workflows/android.yml    # CI: builds the APK, attaches it to releases
├── .github/workflows/rootfs.yml     # CI: builds the rootfs image per architecture
└── docs/
    ├── assets/banner.svg            # README banner
    ├── SELF_BOOTSTRAP_PLAN.md       # Running a Linux rootfs on Android: measurements + plan
    ├── MOBILE_OPTIMIZATIONS.md      # RAM, battery, and Android 12+ process fixes
    └── JUPYTER_CPP_EXPLAINED.md     # In-depth guide on C++ Jupyter kernels
```

---

## 🛠️ Testing Your Installation

Once launched, navigate to `~/OpenVScode_Workspace` in the file explorer:
1. **Python**: Open `hello_python/main.py` and click the **Run** (Play) button.
2. **C++**: Open `hello_cpp/main.cpp` and click **Run**, or run `make run` in the built-in terminal.
3. **Jupyter Notebook**:
   - Open `notebooks/test_python.ipynb` and run the cells.
   - Open `notebooks/test_cpp.ipynb`, select the **C++ (Clang++)** kernel in the top-right corner, and run the C++ cells!

---

## 🔋 Recommended Android Tweaks

For maximum stability:
1. **Disable Battery Optimization**: Set Termux/OpenVScode to "Unrestricted" in Android App Settings.
2. **Android 12+ Phantom Process Killer**: If you experience sudden background kills during heavy compiling, disable phantom process limits (see [docs/MOBILE_OPTIMIZATIONS.md](docs/MOBILE_OPTIMIZATIONS.md)).
3. **Keyboard**: We recommend installing **Hacker's Keyboard** from F-Droid for a full 5-row desktop layout.

---

## 🧑‍💻 Building the Android app locally

Requires JDK 17 and the Android SDK (compileSdk 35).

```bash
cd android
./gradlew assembleDebug
```

`local.properties` is deliberately untracked — Android Studio writes your own `sdk.dir`
into it on first open, or you can set `ANDROID_HOME` in your environment instead.

Every push to `master` runs the same build in CI, and pushing a `v*` tag publishes the
resulting APK to a GitHub Release.

---

## 📄 License
MIT License. Built using open-source tools: VS Code / Code-Server, LLVM/Clang, Project Jupyter, and Open VSX.
