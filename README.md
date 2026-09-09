# 📱 OpenVScode Mobile IDE

> A complete **VS Code** development environment tuned for **Smartphones (Android)** with **Python 3**, **C++ (Clang/LLVM)**, and **Jupyter Notebooks (Python & C++ Kernels)** built directly in.

[![Platform](https://img.shields.io/badge/platform-Android%20%7C%20Linux%20ARM64-green.svg)](#)
[![License](https://img.shields.io/badge/license-MIT-blue.svg)](#)
[![VS Code](https://img.shields.io/badge/editor-Code--Server%20%2F%20OpenVSCode-007acc.svg)](#)
[![Python](https://img.shields.io/badge/python-3.11+-yellow.svg)](#)
[![C++](https://img.shields.io/badge/c++-Clang%2020-red.svg)](#)
[![Jupyter](https://img.shields.io/badge/jupyter-Python%20%26%20C%2B%2B%20Kernels-orange.svg)](#)

---

## ⚡ Quickstart (Install on Phone in 2 Minutes)

### Step 1: Install Termux on your phone
Download **Termux** from [F-Droid](https://f-droid.org/en/packages/com.termux/) (do not use Google Play Store version as it is deprecated).

### Step 2: Clone and Run Installer
Open Termux and paste:

```bash
pkg update && pkg install -y git
git clone https://github.com/YourUsername/OpenVScode.git
cd OpenVScode
bash setup.sh
```

### Step 3: Launch
```bash
./start.sh
```
Open your browser at `http://127.0.0.1:8080`.

> [!TIP]
> **Install as Fullscreen App**: In Chrome/Edge/Brave, tap the three dots (⋮) and select **"Add to Home screen"** or **"Install app"**. It will launch borderless without any address bar, just like a native Android APK!

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
├── setup.sh                         # Master 1-click bootstrap installer
├── start.sh                         # Launcher script with wake-lock & IP display
├── config/
│   ├── settings.json               # Pre-tuned mobile VS Code settings
│   ├── keybindings.json            # Mobile touch shortcuts
│   └── compile_flags.txt           # Clangd include paths & C++20 standard
├── scripts/
│   ├── install_toolchain.sh        # Python 3, Clang/LLVM, Node.js installer
│   ├── install_jupyter_kernels.sh  # Python & C++ Jupyter kernels setup
│   └── install_extensions.sh       # Pre-installs extensions from Open VSX
├── examples/
│   ├── hello_python/               # Sample Python project
│   ├── hello_cpp/                  # Sample C++ project with Makefile
│   └── notebooks/                  # Sample Python & C++ Jupyter notebooks
├── android/
│   ├── README.md                   # Fullscreen PWA and GeckoView APK guide
│   ├── manifest.json               # PWA manifest for standalone app mode
│   └── mobile-keyboard-bar.js      # On-screen touch coding keyboard bar
└── docs/
    ├── MOBILE_OPTIMIZATIONS.md     # RAM, battery, and Android 12+ process fixes
    └── JUPYTER_CPP_EXPLAINED.md    # In-depth guide on C++ Jupyter kernels
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

## 📄 License
MIT License. Built using open-source tools: VS Code / Code-Server, LLVM/Clang, Project Jupyter, and Open VSX.
