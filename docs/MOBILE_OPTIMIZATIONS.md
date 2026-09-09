# Mobile Optimizations & Android Troubleshooting Guide

Running an IDE with Python, C++ (Clang), and Jupyter on a smartphone requires a few specific Android settings to ensure maximum stability, performance, and battery life.

---

## 1. The Android 12+ "Phantom Process Killer" Fix (Critical!)

Starting in Android 12, Google introduced the *Phantom Process Killer*, which automatically terminates apps that spawn more than 32 child processes.
Compilers (`clang++`, `make`, `ninja`) and `node.js` (which VS Code uses) frequently spawn multiple processes and may trigger this limit.

### How to Disable Phantom Process Killing:
If you have a PC with ADB enabled (or using wireless ADB via Termux / Shizuku):
```bash
adb shell "/system/bin/device_config put activity_manager max_phantom_processes 2147483647"
adb shell "/system/bin/device_config set_sync_disabled_for_tests persistent"
```
On Android 14+, you can also toggle:
`Settings -> Developer Options -> Disable child process restrictions`.

---

## 2. Preventing Background Termination (Battery Optimization)

Android's aggressive OEM power managers (MIUI, OneUI, ColorOS) love to kill background services.
To keep your IDE running when you switch apps:
1. Open **Settings -> Apps -> Termux (or your OpenVScode app)**.
2. Go to **Battery -> Unrestricted** (disable battery optimization).
3. Lock the app in your Android Recent Apps switcher (padlock icon).
4. Run `termux-wake-lock` (which `start.sh` does automatically).

---

## 3. Managing RAM on Phones with 4GB - 6GB

Compiling large C++ templates or running multiple Jupyter kernels can consume 500MB - 1.5GB of RAM.

### Recommended RAM Optimizations:
- **Limit Clangd worker threads**: We pre-configured `-j=2` in `config/settings.json` so `clangd` won't saturate all 8 CPU cores.
- **Enable zRAM / Swap**:
  If your phone supports Android RAM Plus / Virtual RAM, keep 2GB-4GB enabled.
  Or inside Termux/Linux:
  ```bash
  # Create a 1GB swapfile if your kernel allows swap
  dd if=/dev/zero of=~/swapfile bs=1M count=1024
  mkswap ~/swapfile
  swapon ~/swapfile 2>/dev/null || echo "Swap requires root or kernel support"
  ```
- **Disable Minimap and Smooth Animations**:
  Pre-configured in `config/settings.json`:
  ```json
  "editor.minimap.enabled": false,
  "editor.glyphMargin": false
  ```

---

## 4. Touchscreen & Keyboard Ergonomics

- **Virtual Keyboard covering code**:
  In VS Code settings, word wrap is enabled (`"editor.wordWrap": "on"`).
- **Hacker's Keyboard**:
  We strongly recommend installing **Hacker's Keyboard** from F-Droid or Play Store. It gives a 5-row full PC layout with arrow keys, Ctrl, Alt, Tab, and Esc.
- **Termux Extra Keys**:
  Add this to `~/.termux/termux.properties` for easy access:
  ```properties
  extra-keys = [['ESC','TAB','CTRL','ALT','UP','DOWN','LEFT','RIGHT'],['{','}','(',')','[',']',';','/']]
  ```
