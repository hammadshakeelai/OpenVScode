# Fix report — "downloads fine, doesn't run fine"

**Date:** 2026-09-10
**Build under test:** v1.0.1 (`versionCode 2`), package `com.openvscode.mobile.debug`
**Reported symptom:** *"it gets stuck on first page for my phone"*
**Status:** root cause identified and confirmed. No fix applied yet — this document is the analysis and the plan.

---

## The one-sentence answer

**The app cannot work as shipped, and no amount of debugging the APK will change that: the IDE server it is built to connect to does not exist anywhere in this repository.**

The APK is a WebView shell. It waits for something to answer on `http://127.0.0.1:8080`, and nothing ever will, because the scripts that were supposed to install and start that server are empty files. The "first page" you are stuck on is the app doing exactly what it was written to do — waiting, forever, for a server that was never built.

This is not a regression and it is not a bad download. The zip fix worked; the bytes on your phone are correct. This is the shell arriving without its engine.

---

## 1. What you are looking at

The screen you're stuck on is `loadingOverlay` in [activity_main.xml](android/app/src/main/res/layout/activity_main.xml) — app icon, blue spinner, **"Starting OpenVScode IDE…"**, and beneath it *"Initializing Python, C++ (Clang), and Jupyter environment"*.

That subtitle is untrue. Nothing is being initialized. The overlay is a static `LinearLayout` drawn on top of an empty WebView, and the only code path in the entire app that can remove it is this one, at [MainActivity.java:114](android/app/src/main/java/com/openvscode/mobile/MainActivity.java#L114):

```java
public void onPageFinished(WebView view, String url) {
    if (url.startsWith("http://127.0.0.1:8080") || url.startsWith("http://localhost:8080")) {
        loadingOverlay.animate().alpha(0f) ... // hide
    }
}
```

`onPageFinished` only fires if a page loads. A page only loads if `webView.loadUrl(SERVER_URL)` is called. That call happens in exactly one place — inside the success branch of the connection poll. **If the poll never succeeds, the overlay is never removed. There is no other exit.**

### The exact timeline on your phone

[`pollServerReadiness()`](android/app/src/main/java/com/openvscode/mobile/MainActivity.java#L125) runs 30 attempts with a 1.5 s sleep after each:

| Time | What happens |
|---|---|
| 0 s | Activity starts, foreground service starts, poll begins |
| 0–45 s | Spinner + "Starting OpenVScode IDE…". Each attempt to reach `127.0.0.1:8080` is refused instantly (nothing is listening on loopback), so the 1.5 s sleeps dominate: 30 × 1.5 s ≈ **45 s** |
| ~45 s | Text flips to **"Waiting for Server..."**, a **Retry Connection** button appears |
| Tap Retry | Re-enters the *identical* 45 s loop, with the identical outcome |

That loop is the bug you're seeing. It is not slow, not hung on a timeout, and not fixable by waiting longer — it is a closed circle.

---

## 2. Root cause, with evidence

### 2.1 Nothing in this repository starts a server

Searched the whole tree for any code that could bind a port or launch a process:

```
$ grep -rnE "Runtime\.|ProcessBuilder|\.exec\(|ServerSocket|NanoHTTPD|getAssets" android/app/src/main/java/
NONE — the app source never starts a process or a socket server
```

Every reference to port 8080 in source is a *consumer* of the server, never a producer:

```
android/app/src/main/java/.../MainActivity.java:32   SERVER_URL = "http://127.0.0.1:8080"   ← polls it
android/app/src/main/java/.../MainActivity.java:116  url.startsWith("http://127.0.0.1:8080") ← checks it
android/README.md:9, README.md:86, README.md:115                                             ← documents it
test-harness/simulator.js:43                          "server online on port 8080"           ← *simulates* it
```

### 2.2 `VScodeService` does not do what its name and its notification claim

This is the most misleading part of the codebase, and it is very likely why the app *feels* like it should work. The service is called **`VScodeService`**, its notification channel is **"OpenVScode Background Server"**, and the notification text reads **"Python, C++, and Jupyter server running in background."**

It runs no server. Read in full, [VScodeService.java](android/app/src/main/java/com/openvscode/mobile/VScodeService.java) does three things and nothing else:

1. `createNotificationChannel()` — registers a channel
2. `acquireLocks()` — takes a `PARTIAL_WAKE_LOCK` and a `WIFI_MODE_FULL_HIGH_PERF` wifi lock
3. `startForeground()` — posts the notification quoted above

There is no fourth step. The notification is asserting the existence of a server that the same file never launches.

### 2.3 The server was meant to come from scripts that are empty

The design intent was Termux-side: `setup.sh` installs the toolchain, `start.sh` launches code-server on 8080, the APK connects to it. Those files exist and are **zero bytes**:

```
$ find . -type f -empty -not -path "./.git/*"
./setup.sh                             0 bytes
./start.sh                             0 bytes
./scripts/install_toolchain.sh         0 bytes
./scripts/install_jupyter_kernels.sh   0 bytes
./scripts/install_extensions.sh        0 bytes
```

**The causal chain is complete:** empty installer → no code-server on the device → poll to `127.0.0.1:8080` refused 30× → `loadUrl` never called → `onPageFinished` never fires → overlay never hides → stuck on the first page, permanently, by construction.

---

## 3. Secondary defects found while tracing this

These are real and worth fixing, but none of them is *the* reason the app doesn't run. I've marked each with how strongly it's established, because some cannot be checked from a Windows workstation.

### 3.1 The server URL is hardcoded in two places — **confirmed**

`SERVER_URL` at [line 32](android/app/src/main/java/com/openvscode/mobile/MainActivity.java#L32) is `private static final`. There is no settings screen, no text field, no intent extra. Even if you already run code-server on a laptop or another device, **this app cannot be pointed at it.**

The trap when fixing this: the URL appears at **two** sites. Line 32 is the poll target; [line 116](android/app/src/main/java/com/openvscode/mobile/MainActivity.java#L116) independently hardcodes `startsWith("http://127.0.0.1:8080")` as the condition for hiding the overlay. Make only line 32 configurable, point it at `http://192.168.1.5:8080`, and you get the worst possible outcome: the poll succeeds, the IDE loads perfectly — and the overlay stays parked on top of it, looking exactly like the bug you started with. **Both sites must change together.**

### 3.2 The failure message tells the user nothing — **confirmed**

After 45 s the app says "Waiting for Server..." — which implies a server is starting and running late. In reality the connection was *refused*, immediately, thirty times. The `catch` block at [line 146](android/app/src/main/java/com/openvscode/mobile/MainActivity.java#L146) is `catch (Exception ignored)`: the exception that would explain everything is discarded. Nothing is written to logcat on the failure path either, so even an adb-equipped user learns nothing.

### 3.3 The wake lock outlives the UI, with no way back — **confirmed**

- `wakeLock.acquire()` is called with **no timeout** ([VScodeService.java:57](android/app/src/main/java/com/openvscode/mobile/VScodeService.java#L57))
- `onStartCommand` returns **`START_STICKY`** — Android restarts the service if it dies
- `MainActivity.onDestroy()` **never calls `stopService`** — it only shuts down its executor
- Back press calls **`moveTaskToBack(true)`**, so the app is backgrounded rather than closed

Net effect: from the moment you launch the app, your phone holds a CPU wake lock and a high-performance wifi lock **for as long as the service lives**, and dismissing the app does not release them. Right now it is burning battery to display a spinner for a server that does not exist.

The only in-app way to stop it is the **"Stop Server"** action on the notification.

### 3.4 `POST_NOTIFICATIONS` is never requested — **confirmed, but its impact is not**

```
$ grep -rnE "requestPermissions|registerForActivityResult" android/app/src/main/java/
NONE — POST_NOTIFICATIONS is declared in the manifest but never requested at runtime
```

The permission is declared in [AndroidManifest.xml](android/app/src/main/AndroidManifest.xml) but the app never prompts for it. On Android 13+ notification permission is denied by default until asked.

**What I can't determine from here:** whether that denial actually hides the *foreground-service* notification on your device. Foreground-service notifications aren't straightforwardly suppressed the way ordinary ones are, and OEM behavior varies. This matters a lot, because it decides whether §3.3 is "a battery drain with an escape hatch" or "a battery drain with no escape hatch short of Force Stop in Settings."

**→ This is the one open question in this report. See §6.**

### 3.5 Cleartext traffic is enabled globally — **confirmed, low severity**

`android:usesCleartextTraffic="true"` applies to *every* host, not just loopback. The clean fix is a scoped `network_security_config.xml`. Note this interacts with §3.1: if you scope cleartext to loopback only, a user-configurable **LAN** address over `http://` immediately breaks. The two changes have to be designed together — permit loopback plus whatever host the user configures, or accept `https` for remote.

### 3.6 The touch keybar may not reach Monaco — **unverified, and untestable until a server exists**

[`handleKeyPress`](android/app/src/main/java/com/openvscode/mobile/MainActivity.java#L223) builds `KeyboardEvent`s with `key`, `code`, `ctrlKey`, `altKey` — but not `keyCode`/`which`. VS Code's `StandardKeyboardEvent` reads `keyCode`; without it, Ctrl-combinations may resolve to `KeyCode.Unknown` and do nothing. The `code` values are also questionable (`code: '{'` is not a valid `KeyboardEvent.code`).

I am *not* calling this a confirmed defect — it cannot be tested until something is actually loaded in the WebView. Two notes for whoever picks it up:

- `android/mobile-keyboard-bar.js` has the same shape and would need the same correction.
- `test-harness/` appears to have been built to probe exactly this (`TEST_ESC_DISPATCH`, `TEST_TAB_INDENT`, `TEST_FOCUS_RETENTION`) and is the natural place to verify a fix without a phone.

---

## 4. The fix, in two tiers

The honest split: **Tier A** makes the shell truthful and genuinely useful within a day. **Tier B** is the actual product.

### Tier A — make the shell work with a server that already exists

Nothing here requires writing an installer. It turns the APK from "broken" into "a real client for any code-server you can already reach." Roughly 150 lines.

| # | Change | Fixes |
|---|---|---|
| A1 | Add a server-address field to the loading screen, persisted via `SharedPreferences`, defaulting to `http://127.0.0.1:8080`. **Update both line 32 and line 116** — derive the `onPageFinished` check from the configured value rather than a literal | §3.1 |
| A2 | On failure, report what actually happened: the address tried, the attempt count, and the real exception (`ConnectException: Connection refused` → *"Nothing is listening on that address"*). Log it to logcat too | §3.2 |
| A3 | Do not start `VScodeService` in `onCreate`. Start it only after the WebView successfully loads, and `stopService` in `onDestroy` when it hasn't. Add a timeout to `wakeLock.acquire()` | §3.3 |
| A4 | Request `POST_NOTIFICATIONS` on first launch via `registerForActivityResult` | §3.4 |
| A5 | Rewrite the loading copy so it stops asserting things that aren't happening — "Connecting to <address>…", not "Initializing Python, C++ (Clang), and Jupyter environment" | §2.2 |
| A6 | Replace global cleartext with a scoped `network_security_config.xml`, designed alongside A1 | §3.5 |

After Tier A, someone running code-server in Termux — or on a laptop on the same wifi — can type the address in and get a working mobile IDE. That is a real product, and it's testable.

### Tier B — write the server provisioning that was always missing

This is the genuine fix for "doesn't run," and it is the larger piece of work:

- `scripts/install_toolchain.sh` — Python 3, Clang, build tooling under Termux
- `scripts/install_jupyter_kernels.sh` — `ipykernel` + the C++ kernel
- `scripts/install_extensions.sh` — Open VSX extensions
- `setup.sh` — orchestrate the above
- `start.sh` — launch code-server bound to `127.0.0.1:8080`, with the wake-lock handling the README describes

**A caveat I want to state plainly rather than discover later:** none of this can be verified from this Windows machine, and CI has no Termux/aarch64 runner. Writing these scripts without a device to run them on produces code that *looks* right and fails on contact. If you want Tier B, the realistic path is to develop it directly on the phone in Termux, or to accept a first draft explicitly labelled untested.

### What I'd recommend

**Do Tier A now, and re-frame the project around it.** It is small, it is verifiable, and it converts the APK from a dead end into a legitimate mobile client for code-server — which is genuinely useful and is a category of app people want. Then treat Tier B as the roadmap item it actually is, developed on-device.

---

## 5. What is *not* wrong

Worth recording, so this ground doesn't get re-covered:

- **The download and the APK are fine.** The zip fix works; the bytes on your phone match CI's build exactly (SHA-256 verified against `SHA256SUMS.txt`, and CI fails the build if the zip doesn't extract to a byte-identical APK).
- **The app is not crashing.** "Stuck on the first page" confirms it. `Theme.OpenVScode` correctly extends `Theme.MaterialComponents.DayNight.NoActionBar`, every ID referenced by `findViewById` exists in the layout, and every `R.string` referenced resolves. A theme or resource mismatch would have force-closed on launch instead.
- **The manifest is sound.** Permissions are correct for the intent, and the Android 14+ `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` property that `foregroundServiceType="specialUse"` requires is properly declared.
- **The build is healthy.** AGP 8.7.3 / Gradle 8.9 / compileSdk 35, debug-signed, CI green.

Every one of these was a plausible cause of "doesn't run." All were ruled out by inspection, which is what leaves §2 standing alone.

---

## 6. One open question

**On your phone: does a persistent notification titled "OpenVScode IDE Active" appear — with a "Stop Server" button — while the app is open?**

- **If yes** → §3.4 is cosmetic, and you have a working way to stop the wake lock right now: pull down the shade and tap **Stop Server**.
- **If no** → the notification permission denial *is* suppressing it, §3.3 and §3.4 compound, and the only way to release the wake lock today is **Settings → Apps → OpenVScode → Force Stop**. That would promote A3 and A4 to the top of the queue.

**In the meantime, regardless of the answer:** force-stop the app when you're not testing it. It is holding a CPU wake lock to render a spinner.

---

## Appendix — reproduction

No device required; this is a static-analysis result and reproduces by reading:

1. `MainActivity:77` → `pollServerReadiness()` on launch
2. `MainActivity:131` → `GET http://127.0.0.1:8080`, 30 attempts, 1.5 s apart
3. `grep -rnE "ServerSocket|ProcessBuilder|Runtime\.|\.exec\(" android/app/src/main/java/` → no matches
4. `find . -type f -empty` → `setup.sh`, `start.sh`, `scripts/*.sh` are all 0 bytes
5. Therefore the condition at `MainActivity:138` is unsatisfiable → `webView.loadUrl` at `:142` is unreachable → `onPageFinished` at `:114` never fires → `loadingOverlay` is never hidden

To watch it live on a connected device:

```bash
adb logcat -s MainActivity:I VScodeService:I
```

You will see `VScodeService` report that it started with the wake lock active, and you will never see `MainActivity`'s `"Server reached. Loading into WebView..."`.
