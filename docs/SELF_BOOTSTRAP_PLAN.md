# Self-bootstrapping IDE — feasibility and plan

**Goal:** install one APK, tap once, get Python, C++ and Jupyter. No Termux, no
F-Droid, no shell commands.

**Status:** the load-bearing assumption is now measured, not assumed. The design
below follows from that measurement.

---

## 1. The constraint, and what was actually measured

Since Android 10, an app targeting API 29+ cannot `execve()` a file it wrote
into its own data directory — a W^X violation. Files there carry the SELinux
label `app_data_file`, which the `untrusted_app` domain may read and write but
not execute. This is why Termux pins to an old target API and why its Play
Store build was abandoned.

That would appear to sink a downloaded rootfs entirely. It does not.

**Measured on an API 36 emulator, inside the real app process, targetSdk 35:**

```
copied=611200 chmod=true
DIRECT_EXEC: BLOCKED -> java.io.IOException: error=13, Permission denied
LINKER_EXEC: exit=0 out=LINKER_OK
nativeLibraryDir=/data/app/~~…/lib/x86_64
```

Two facts, both load-bearing:

1. **Direct execution is blocked**, exactly as documented.
2. **`/system/bin/linker64 <path-to-binary>` executes it successfully.** Invoking
   the system dynamic linker explicitly is not an `execve` of the target file, so
   the SELinux rule does not apply.

An earlier attempt to test this via `adb shell run-as` reported success for
*both* cases and was discarded: `run-as` runs in the `runas_app` domain, not
`untrusted_app`, so it cannot answer this question. Only an in-process test can.

### The catch

The linker trick covers processes *we* launch. It does not cover their children.
Once `bash` is running, its own `execve("gcc", …)` goes straight to the kernel
and is blocked. A rootfs where nothing can spawn anything is useless.

---

## 2. Architecture

**An `LD_PRELOAD` shim that rewrites `execve` to go through the linker.**

A small shared library, preloaded into the first process, intercepts `execve`
and `execvp`. When the target lives in our rootfs, it rewrites the call to
`/system/bin/linker64 <target> <args…>`. Children inherit `LD_PRELOAD`, so the
whole process tree is covered from one hook.

This is the approach Termux uses (`termux-exec`). It is a few hundred lines of C
rather than the alternative — shipping `proot`, which needs `talloc`, Android
patches, and imposes a real ptrace performance cost on every syscall.

```
APK (small, ~8 MB)
├── jniLibs/arm64-v8a/
│   └── libexechook.so          LD_PRELOAD shim, built here with NDK 29
└── first launch
    └── downloads rootfs.tar.xz  (~120–200 MB, hosted on GitHub Releases)
        └── extracts to filesDir/rootfs
            └── launched as:
                /system/bin/linker64 <rootfs>/bin/bash
                  with LD_PRELOAD=libexechook.so
                       PATH, HOME, PREFIX pointed into the rootfs
```

Everything inside the rootfs — `code-server`, `python3`, `clang++`, `jupyter` —
then runs normally, on 127.0.0.1:8080, which the app already discovers by itself.

### Why build the shim rather than ship a prebuilt binary

NDK 26/28/29 and cmake 3.22 are installed here, so the shim is compiled from
source in this repo. Shipping someone else's prebuilt `proot` would put an
unaudited third-party binary inside the APK, which is a supply-chain decision
this project should not make silently.

---

## 3. Phases

| Phase | Deliverable | Risk |
|---|---|---|
| **1** | `libexechook.so` builds with the NDK; an in-app test proves a child process spawned from a shell in the rootfs can itself exec | **This is the whole risk.** If the shim does not cover the tree, the design changes to proot |
| **2** | Rootfs build script (Alpine or Debian arm64) producing a tarball with Python, Clang, Node | Package availability on musl vs glibc; Debian is safer, Alpine is far smaller |
| **3** | In-app download + verified extraction, resumable, with a real progress UI | Large download on mobile data; needs a checksum and a resume path |
| **4** | `code-server` inside the rootfs, started on 127.0.0.1:8080 | Node on Android; the existing auto-discovery already handles the connect |
| **5** | Jupyter kernels (Python + C++) | Native builds are slow; may need prebuilt wheels in the rootfs |

Phase 1 is the only phase that can invalidate the design, so it goes first and
alone. Nothing else is worth building until a grandchild process can exec.

---

## 4. Honest risks

- **Size.** The rootfs is 120–200 MB compressed. That is a real download, and
  it must be resumable and checksummed.
- **ABI.** Phones are `arm64-v8a`; the emulator here is `x86_64`. Both need
  building, and phase 1 must be re-verified on a physical device.
- **This is not testable end-to-end from Windows.** The emulator gets us
  further than before — it already proved the exec constraint and the
  auto-discovery — but the arm64 rootfs needs a real phone.
- **Play Store.** The linker technique is explicitly against Play policy
  (running code downloaded outside the store). Irrelevant while distributing
  via GitHub Releases; it permanently forecloses Play distribution.

---

## 5. What works today, without any of this

v1.0.5 already provisions the full stack through Termux: install Termux from
F-Droid, tap **Set up Python, C++ & Jupyter automatically**, and the app drives
`setup.sh` itself via Termux's `RUN_COMMAND` service. That path is written and
shipped. This plan exists to remove the Termux dependency, not to replace a
stack that does not work.
