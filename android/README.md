# OpenVScode - Android Integration & Fullscreen App Options

This directory provides configurations to run OpenVScode as a dedicated, standalone mobile app without browser address bars, tabs, or distraction.

---

## Option 1: PWA (Progressive Web App) - Recommended & Zero Installation
1. Start the server on your phone via `./start.sh` or in Termux.
2. Open Chrome, Edge, or Brave and navigate to `http://127.0.0.1:8080`.
3. Tap the browser menu (⋮ three dots) and select:
   - **"Install app"** or **"Add to Home screen"**.
4. The phone will create a dedicated **OpenVScode** app icon on your home screen.
5. Launching from this icon opens VS Code in **full-screen immersive mode** (standalone window, no browser URL bar or navigation buttons).

---

## Option 2: Mobile Virtual Key Bar (Coding Touch Bar)
Smartphones lack physical `Esc`, `Tab`, `Ctrl`, `Alt`, curly braces `{ }`, and arrow keys.
You have two ways to get these on your phone:

### A. Termux Extra Keys Row (Built-in)
Termux already includes an extra keys row above your virtual keyboard.
To customize it for programming, edit `~/.termux/termux.properties`:
```properties
extra-keys = [['ESC','TAB','CTRL','ALT','UP','DOWN','LEFT','RIGHT'],['{','}','(',')','[',']',';','/']]
```
Then run:
```bash
termux-reload-settings
```

### B. Injectable Web Touch Bar
We included `mobile-keyboard-bar.js`. If you use a custom WebView app or browser extension/Tampermonkey, injecting this script displays a native dark-mode touch toolbar at the bottom of the screen with `ESC`, `TAB`, `CTRL`, `ALT`, braces, quotes, and arrow keys.

---

## Option 3: GeckoView Native APK Wrapper
Chromium-based WebViews on Android occasionally struggle with virtual keyboard height changes and caret positioning. If you prefer a native Android APK wrapper:
- We recommend cloning **[`sky130/CodeServer`](https://github.com/sky130/CodeServer)**, which uses Mozilla's **GeckoView** engine instead of Chromium WebView.
- It seamlessly talks to `http://127.0.0.1:8080` while handling virtual keyboard resizing without UI glitches.
