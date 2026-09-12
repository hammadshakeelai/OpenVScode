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
| ~~**1**~~ | ~~`libexechook.so` builds with the NDK; an in-app test proves a child process spawned from a shell in the rootfs can itself exec~~ | **PASSED — see §6** |
| ~~**2**~~ | ~~Rootfs build script producing a tarball with Python, Clang, Node~~ | **BUILT — see §8** |
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


---

## 6. Phase 1 result — PASSED

Built `libexechook.so` for `arm64-v8a` and `x86_64` with NDK 27 via CMake, and
ran it as a controlled experiment on an API 36 x86_64 emulator. Identical
commands, differing only in whether the shim was preloaded:

```
1. control: system shell -> rootfs binary, NO hook
     exit=126  output=/system/bin/sh: …/rootfs/bin/echo: Permission denied
2. same command WITH hook preloaded
     exit=0    output=HOOKED_RAN
3. grandchild: shell -> shell -> rootfs binary
     exit=0    output=GRANDCHILD_RAN
4. via PATH lookup rather than an absolute path
     exit=0    output=PATH_RAN
```

The control failing at exit 126 is what makes the rest meaningful: without it
the test would prove only that `echo` runs.

**Case 3 was the actual risk** and it is settled. `LD_PRELOAD` is inherited, so
one shim covers an arbitrarily deep process tree — a shell can spawn a shell
that spawns a compiler. logcat shows `exechook: loaded` once per process down
the chain, confirming interposition rather than a lucky pass.

**Case 4** confirms the PATH search had to be reimplemented: bionic resolves
`execvp()` internally without going through the PLT, so interposing `execve()`
alone would have missed it.

The design stands. **proot is not needed**, which removes a third-party binary
from the APK and the ptrace cost from every syscall.

### Reproducing

The probe ships in the app, off by default:

```
adb shell am start -n com.openvscode.mobile.debug/com.openvscode.mobile.MainActivity --ez run_probe true
adb logcat -d -s PHASE1:* exechook:*
```

### Still unverified

Phase 1 ran on **x86_64**. The `arm64-v8a` library builds but has not executed
on real hardware, and phones are arm64. Re-run the probe on a physical device
before Phase 2 is worth starting — the SELinux policy is the same, so this is
expected to pass, but "expected" is what Phase 1 existed to replace.


---

## 7. Phase 2 opening measurements — the design has to change

Phase 2 began by building a rootfs. It stopped immediately, because a Phase 1
conclusion turned out to be narrower than it was written.

### 7.1 The linker trick does not extend to glibc

Phase 1 proved the linker rewrite using **toybox**, which is an Android binary
linked against **bionic**. Every real rootfs — Debian, Ubuntu, Alpine — is
linked against **glibc** or **musl**. That is a different question, and it was
never asked.

A glibc `echo` taken from Ubuntu (`PT_INTERP=/lib64/ld-linux-x86-64.so.2`,
`DT_NEEDED libc.so.6`) was pushed into the app data directory and run at
targetSdk 35:

```
5.  glibc binary, direct            -> EACCES (Permission denied)
5b. glibc binary via linker64       -> exit=1
    CANNOT LINK EXECUTABLE: library "libc.so.6" not found: needed by main executable
```

Android's linker *is* bionic. It cannot satisfy `libc.so.6`, so it cannot load
a glibc executable no matter how it is invoked. **"proot is not needed" was true
only for bionic binaries**, and the rootfs we want is not bionic.

### 7.2 targetSdk 28 restores execution

The same probe, same device, only `targetSdk` changed from 35 to 28:

```
1.  control: system shell -> rootfs binary, NO hook   -> exit=0  CONTROL_RAN
5.  glibc binary, direct                              -> error=2  No such file or directory
```

Two things changed, and the second is the more informative:

1. **Direct exec from the app data directory works again.** Apps targeting ≤ 28
   run in the `untrusted_app_27` SELinux domain, which still holds `execute` on
   `app_data_file`. That compatibility domain is alive on API 36.
2. **The glibc failure changed from `EACCES` to `ENOENT`.** Permission is no
   longer the obstacle; the kernel simply cannot find the ELF interpreter at
   `/lib64/ld-linux-x86-64.so.2`, a path we cannot create outside our sandbox.

`ENOENT` on the interpreter is precisely the problem `proot` solves: it
virtualizes path resolution so `/lib64/...` lands inside the rootfs.

The APK built at targetSdk 28 installed and ran normally on API 36.

### 7.3 Revised architecture

| | targetSdk 35 + exec shim | targetSdk 28 + proot |
|---|---|---|
| Bionic binaries | works (proved) | works |
| glibc rootfs (Debian) | **impossible** — bionic linker cannot load glibc | works, standard approach |
| Extra components | `libexechook.so` (built, working) | `proot` built for Android |
| Play Store | already foreclosed | already foreclosed |

The right combination is **targetSdk 28 + proot + a glibc rootfs**. This is what
UserLAnd and Andronix do, and these measurements explain why they all do it that
way rather than something lighter.

`libexechook.so` was correct and tested, and is what makes a *bionic* rootfs
viable at a modern targetSdk. It is simply not the tool for a Debian rootfs, and
the project went to Termux instead, so it no longer ships: the shim, the rootfs
installer and the image tooling were removed once Termux became the runtime. Git
history keeps them, at `android/app/src/main/cpp/` and `tools/rootfs/`.

### 7.4 The open decision

Dropping to targetSdk 28 affects the entire app, not just this feature, so it is
not a change to make quietly:

- **For:** it is the only measured path to a no-Termux rootfs; it also removes
  the Android 13 notification-permission dance, since pre-33 behaviour applies.
- **Against:** it forecloses Play Store distribution permanently (already
  foreclosed by the linker technique), some devices show an "older version of
  Android" notice at install, and modern platform behaviours are opted out of
  app-wide.

Phase 2's rootfs build is required under either answer, so it proceeds while
this is decided.


---

## 8. Phase 2 result — rootfs built

`targetSdk` is now **28**, decided deliberately (§7.4). The rootfs builds in CI
for both architectures via qemu and publishes with checksums.

### What is in it

Verified by running the image natively in the amd64 job:

```
Python 3.11.2
Debian clang version 14.0.6
Available kernels:
  cpp03  cpp11  cpp14  cpp17  cpp20  cpp23  cpp98
  python3
```

Seven C++ standards plus Python, which is more than the original brief asked
for. code-server, Node and the toolchain are present.

The arm64 job cannot execute its own image — the runner is amd64 and qemu only
emulates the build — so those versions are asserted from the amd64 build of the
same Dockerfile, not measured on arm64.

### Size — the open problem

| Image | Compressed |
|---|---|
| `rootfs-arm64.tar.gz` | **402 MB** |
| `rootfs-amd64.tar.gz` | **427 MB** |

That is a large first-run download on a phone, and it is the main thing standing
between this and something pleasant to use. Options, roughly in order of return:

1. **Switch gzip → xz or zstd.** Likely 30–40% smaller. Costs a decompressor in
   the APK, since the JDK only has gzip built in. gzip was chosen to keep the
   Android side simple; that trade now looks wrong at this size.
2. **Drop `llvm`**, keeping only `clang`. The full LLVM toolchain is a large
   part of the image and little of it is used to compile a single file.
3. **Split the download.** Ship Python + Clang first so the IDE is usable, and
   fetch Jupyter on demand.
4. **Trim code-server.** It carries built-in extensions that make no sense on a
   phone.

None of these are blocking — the image is correct — so they are optimisation,
not correctness.

### Notification regression from targetSdk 28

Found by testing rather than by reasoning. On API 36 a targetSdk-28 app:

- calling `requestPermissions(POST_NOTIFICATIONS)` is **auto-denied with no
  dialog**, and
- sits at `importance=NONE`, so the foreground-service notification never posts.

The notification carried the **Stop Session** action, so it is no longer a
reliable way to end a session. Guarding the request on `targetSdkVersion` was
tried and reverted: it stops the app asking on devices where asking would work.
The wake lock is instead bounded by `onDestroy()` stopping the service and by
its own 30-minute timeout. A user who wants the notification can enable it under
Settings → Apps → OpenVScode → Notifications.

This is a genuine cost of targetSdk 28 and is recorded here rather than left to
be rediscovered.

### Next

Phase 3: download and extract on-device, with checksum verification and resume.
Phase 4 needs `proot`, which is not yet built — that is the next real unknown,
and the arm64 rootfs has still never executed on hardware.


---

## 9. Phase 4 — how far it got, and the wall it hit

Everything below was measured on an **x86_64 API 36 emulator** with the real
190 MB image installed.

### 9.1 What works

| Step | Result |
|---|---|
| Download, checksum, resume, extract 38,630 entries | works |
| Patched ELF interpreters (388 executables) | works |
| Patched shebangs (486 scripts) | works |
| Repointed absolute symlinks (236) | works |
| `bash`, `python3.11`, `node`, `clang` run from the image | works |
| **code-server serves `127.0.0.1:8080`** | **works — HTTP 302** |

That last line is the important one. The whole stack — Debian, glibc, Node,
code-server — runs on Android and answers HTTP. The image is correct.

### 9.2 The wall

It only works when launched through `adb shell run-as`. Launched by the app
itself, every glibc binary is killed instantly:

```
IDE process exited with code 159        # 128 + 31 = SIGSYS
"Bad system call"
```

Isolated to a single command each, so there is no ambiguity:

| binary | launched by the app |
|---|---|
| bionic `toybox true` | exit 0 |
| glibc `ld.so --version` (x86_64) | exit 0 |
| glibc `ld.so --version` (arm64) | exit 0 |
| **glibc `true` (x86_64)** | **exit 159 — SIGSYS** |

The dynamic loader itself is fine. The kill happens once libc initialises and
the program actually runs. `GLIBC_TUNABLES=glibc.pthread.rseq=0` does not help,
so it is not rseq.

**Cause: Android's seccomp filter.** App processes inherit a syscall allow-list
from zygote, and glibc's startup uses something outside it. `run-as` runs in the
`runas_app` domain with a different policy, which is exactly why the same binary
succeeds there and fails from the app — and why this took so long to see.

This is not a path problem, a patchelf problem, or a permissions problem. Those
were all real and are all fixed. This one is the platform.

### 9.3 What is not known

**Whether a real arm64 phone is affected.** Everything here ran on an x86_64
emulator, and Android's seccomp policies are defined per architecture. The
arm64 policy is the one exercised by every shipping device; the x86_64 one is
essentially emulator-only. UserLAnd and Andronix do run Debian userlands from
an app on real arm64 hardware, which suggests the arm64 filter is more
permissive — but that is inference, not measurement.

The arm64 loader ran to exit 0 here under the emulator's arm64 translation. A
full arm64 glibc program could not be tested, because the rest of that image is
not installed.

**This is the single question worth answering next, and only a physical device
can answer it.**

### 9.4 If arm64 is also blocked

- **A musl rootfs (Alpine).** musl's syscall usage is far more conservative than
  glibc's, so it may fit inside the filter where glibc does not. Costs
  compatibility: code-server's official builds are glibc.
- **A bionic userland**, which is what Termux is, and why Termux works. That
  means building every package against bionic — effectively rebuilding Termux,
  which §4 already rejected as out of scope.
- **Keep Termux as the engine**, which v1.0.5 already does and which needs none
  of this.


---

## 10. The arm64 answer: blocked, and not because of glibc

Measured on a physical **Samsung Galaxy S23 Ultra (SM-S918U, arm64-v8a)**,
Android 15, with the app launching each binary itself.

| launched by the app | exit |
|---|---|
| bionic `toybox true` | **0** |
| arm64 glibc loader `--version` | **0** |
| arm64 glibc `true` (with `libc.so.6` present) | **159 — SIGSYS** |
| musl loader `--version` | 1 |
| musl `busybox true` via its loader | **159 — SIGSYS** |

Two conclusions, and the second is the one that matters.

**arm64 is blocked exactly like x86_64.** This was the open question from §9.3
and the answer is no — the emulator was not the problem. Both architectures
behave identically.

**It is not a glibc problem.** musl is killed the same way. Android's app
seccomp filter rejects something both non-bionic libcs do during program
startup, while the dynamic loader on its own is fine in both cases. So swapping
Debian for Alpine, which §9.4 listed as the fallback, would not have helped —
it would have cost a rebuild to learn the same thing.

The only userland that runs unmodified under an Android app's seccomp filter is
**bionic**. That is what Termux is, and it is why Termux works.

### What this means for the design

The self-bootstrapping rootfs, as built, cannot start its IDE. Everything
around it is sound and verified — the image is correct, code-server genuinely
serves HTTP from inside it, and the download/verify/extract path works on real
hardware. The single missing step is that an Android app may not execute those
binaries.

Three ways forward, in order of how much they cost:

1. **Termux as the engine.** Already shipped, already works, needs none of this.
   The app drives the whole install through Termux's RUN_COMMAND service.
2. **proot.** Worth revisiting for a reason that only became clear here: a
   ptracer can intercept `SIGSYS` and emulate the rejected syscall. That is
   plausibly how UserLAnd and Andronix run Debian userlands from an app on real
   hardware — not for path virtualisation, which the ELF patching already
   solved, but as a seccomp escape. Unproven, and proot still has to be built
   for Android.
3. **A bionic userland** — rebuilding Termux's package set. Rejected in §4 as
   out of scope, and nothing here changes that.

### What was not wasted

The ELF interpreter patching, shebang rewriting and symlink repointing are all
correct and would be needed by any of these paths. So is the installer. The
runtime guard caught two corrupt images before publication. And the image
shrank from 402 MB to 112 MB along the way.

What was wrong was the assumption that a normal Linux userland can execute
under an Android app. It cannot, on either architecture, with either libc.
