<p align="center">
  <img src="docs/assets/banner.svg" alt="OpenVScode Mobile IDE — a full VS Code environment that runs on your phone, with Python 3, C++ (Clang) and Jupyter kernels" width="100%">
</p>

<p align="center">
  <a href="https://github.com/hammadshakeelai/OpenVScode/releases/latest/download/OpenVScode-Mobile.apk">
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

**On the phone, tap this link:**

### → [**Download OpenVScode-Mobile.apk**](https://github.com/hammadshakeelai/OpenVScode/releases/latest/download/OpenVScode-Mobile.apk) ←

That link always resolves to the newest build, so it never goes stale. Then:

1. Open the download from the notification shade, or **Files → Downloads**.
2. Android will ask to allow installs from your browser — tap **Settings**, turn on
   **Allow from this source**, go back, and tap **Install**.
3. Launch **OpenVScode** from your app drawer.

<details>
<summary><b>Other ways to get the APK</b></summary>

- **Browse every version:** the [Releases page](https://github.com/hammadshakeelai/OpenVScode/releases)
  lists each build with its notes.
- **Bleeding edge:** every push to `master` uploads an APK to its
  [Actions run](https://github.com/hammadshakeelai/OpenVScode/actions/workflows/android.yml)
  as a build artifact. GitHub requires you to be signed in to download artifacts and
  hands you a `.zip` you have to unpack, so the release link above is the one that
  works with a single tap on a phone.
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

> [!WARNING]
> **The installer scripts are not written yet.** `setup.sh`, `start.sh` and everything
> under `scripts/` are currently empty placeholder files, so the commands in this
> section will not do anything yet. The tuned configuration in `config/`, the examples,
> and the Android app are all real — this bootstrap path is the piece still outstanding.
> Contributions welcome via the [issue tracker](https://github.com/hammadshakeelai/OpenVScode/issues).

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
├── setup.sh                         # 1-click bootstrap installer   (empty — see warning above)
├── start.sh                         # Launcher with wake-lock & IP   (empty — see warning above)
├── config/
│   ├── settings.json                # Pre-tuned mobile VS Code settings
│   ├── keybindings.json             # Mobile touch shortcuts
│   └── compile_flags.txt            # Clangd include paths & C++20 standard
├── scripts/                         # Toolchain / kernel / extension installers (empty — see above)
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
├── .github/workflows/android.yml    # CI: builds the APK, attaches it to releases
└── docs/
    ├── assets/banner.svg            # README banner
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
