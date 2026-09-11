# One test only you can run

Everything in the no-Termux path works except one step, and the emulator cannot
tell us whether that step works on a real phone. This is the test that settles
it. It takes about two minutes.

## What we already know

On an x86_64 Android emulator, with the full image installed:

| | |
|---|---|
| Download, verify, unpack 38,630 files | works |
| `bash`, `python3.11`, `node`, `clang` from the image | works |
| **code-server serving `127.0.0.1:8080`** | **works — HTTP 302** |
| The app launching any of it | **fails: exit 159 (SIGSYS)** |

The whole Linux stack genuinely runs on Android. It only runs when started from
`adb shell run-as`. Started by the app itself, Android's **seccomp filter** kills
every glibc binary instantly.

Android defines that filter per CPU architecture. The emulator is x86_64; your
phone is arm64, which is the architecture every shipping device uses and the one
projects like UserLAnd and Andronix rely on. So the arm64 filter may well be
more permissive. May. Nobody here has measured it.

## The test

Connect your phone with USB debugging on, then run these.

**1. Install the app**

```bash
adb install -r OpenVScode-Mobile.apk
```

**2. Fetch the arm64 loader and a minimal glibc program**

```bash
curl -L -o rootfs-arm64.tar.xz https://github.com/hammadshakeelai/OpenVScode/releases/download/rootfs-latest/rootfs-arm64.tar.xz
```

```bash
tar -xf rootfs-arm64.tar.xz ./usr/bin/true ./usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1
```

**3. Push them to where the app expects them**

```bash
adb push usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1 usr/bin/true /data/local/tmp/
```

```bash
adb shell "run-as com.openvscode.mobile.debug sh -c 'R=/data/data/com.openvscode.mobile.debug/files/rootfs; mkdir -p \$R/lib/aarch64-linux-gnu \$R/bin \$R/usr/local/bin; cat /data/local/tmp/ld-linux-aarch64.so.1 > \$R/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1; cat /data/local/tmp/true > \$R/bin/true; chmod 755 \$R/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1 \$R/bin/true; printf \"#!/system/bin/sh\nexec \$R/bin/true\n\" > \$R/usr/local/bin/start-ide; chmod 755 \$R/usr/local/bin/start-ide; touch \$R/.installed; echo staged'"
```

**4. Launch the app and read the exit code**

```bash
adb logcat -c && adb shell am start -n com.openvscode.mobile.debug/com.openvscode.mobile.MainActivity
```

Wait about twenty seconds, then:

```bash
adb logcat -d -s RootfsLauncher:*
```

## Reading the result

Look for the line `IDE process exited with code N`.

- **`exited with code 0`** — arm64 is not affected. The seccomp problem is an
  x86_64 emulator artifact, the design is sound, and the remaining work is
  ordinary: publish the arm64 image and wire up the install. This is the good
  outcome and it is a real possibility.

- **`exited with code 159`** — arm64 is blocked the same way. glibc cannot run
  under an Android app's seccomp filter at all, and the no-Termux path needs a
  different userland: musl (Alpine), whose syscall use is far more conservative,
  or bionic, which is what Termux is and why Termux works. See
  `docs/SELF_BOOTSTRAP_PLAN.md` §9.4.

Either answer is useful. 159 would mean months of the current approach was the
wrong tree, and better to know now than after building the rest of it.

## Meanwhile

v1.0.6 already provisions Python, C++ and Jupyter through **Termux** — install
Termux from F-Droid, tap **Set up Python, C++ & Jupyter automatically**, and the
app drives the whole install itself. That path does not touch any of this and is
the one to use today.
