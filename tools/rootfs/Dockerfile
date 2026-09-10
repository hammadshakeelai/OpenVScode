# Builds the Linux rootfs the Android app downloads on first launch.
#
# Debian rather than Alpine on purpose. The bulk of the image is code-server,
# so Alpine's smaller base saves proportionally little, and musl costs real
# compatibility: code-server's official builds are glibc, and many Python
# wheels still have no musllinux variant. Compatibility is worth more than the
# ~45 MB the base would save, especially since this cannot be debugged from a
# developer machine once it is on a phone.
#
# Exported with `docker export`, which flattens to the container's final
# filesystem. That matters for size: files deleted in a later RUN really are
# absent from the export, so the cleanup at the bottom genuinely pays.
FROM debian:bookworm-slim

ENV DEBIAN_FRONTEND=noninteractive
ENV LANG=C.UTF-8

# Deliberately NOT installed:
#   llvm    — the full LLVM tool suite plus a very large set of static archives.
#             Compiling a single file needs clang, not llvm-*.
#   nodejs  — code-server ships its own node; Debian's adds a second copy.
#   npm     — large, and nothing here installs packages from it at runtime.
RUN set -eux; \
    apt-get update; \
    apt-get install -y --no-install-recommends \
        ca-certificates curl wget git xz-utils \
        python3 python3-pip python3-venv python3-dev \
        clang lld make cmake pkg-config \
        libstdc++6 zlib1g \
        procps less nano; \
    apt-get clean; \
    rm -rf /var/lib/apt/lists/* /usr/share/doc/* /usr/share/man/* /usr/share/locale/*

# code-server, from the official standalone release for this architecture.
ARG CODE_SERVER_VERSION=4.96.4
RUN set -eux; \
    arch="$(dpkg --print-architecture)"; \
    case "$arch" in \
        arm64)  cs_arch=arm64 ;; \
        amd64)  cs_arch=amd64 ;; \
        *) echo "unsupported architecture: $arch" >&2; exit 1 ;; \
    esac; \
    url="https://github.com/coder/code-server/releases/download/v${CODE_SERVER_VERSION}/code-server-${CODE_SERVER_VERSION}-linux-${cs_arch}.tar.gz"; \
    curl -fsSL "$url" -o /tmp/code-server.tar.gz; \
    mkdir -p /opt/code-server; \
    tar -xzf /tmp/code-server.tar.gz -C /opt/code-server --strip-components=1; \
    rm /tmp/code-server.tar.gz; \
    ln -sf /opt/code-server/bin/code-server /usr/local/bin/code-server; \
    # code-server bundles a node runtime; expose it rather than installing a
    # second one from apt.
    if [ -x /opt/code-server/lib/node ]; then \
        ln -sf /opt/code-server/lib/node /usr/local/bin/node; \
    fi; \
    code-server --version

# Jupyter. `notebook` rather than the full JupyterLab: markedly smaller, and the
# app renders it in a WebView on a phone screen either way.
RUN set -eux; \
    pip3 install --no-cache-dir --break-system-packages \
        notebook ipykernel; \
    python3 -m ipykernel install --name python3 --display-name "Python 3"; \
    pip3 install --no-cache-dir --break-system-packages jupyter-cpp-kernel || \
        echo "WARNING: jupyter-cpp-kernel unavailable; C++ still compiles from the terminal"

# Loopback only, no password. Nothing off-device can reach it, and the app
# cannot prompt for a password it has no way to know.
RUN set -eux; \
    mkdir -p /root/.config/code-server /root/workspace; \
    printf 'bind-addr: 127.0.0.1:8080\nauth: none\ncert: false\n' \
        > /root/.config/code-server/config.yaml

COPY start-ide.sh /usr/local/bin/start-ide
RUN chmod +x /usr/local/bin/start-ide

# --- size reduction -------------------------------------------------------
# Everything below is deletion. Ordered by what the measurement showed to be
# worth removing; see docs/SELF_BOOTSTRAP_PLAN.md.
RUN set -eux; \
    # Headers and build-time-only packages. pip has already built what it needs.
    apt-get purge -y python3-dev pkg-config 2>/dev/null || true; \
    apt-get autoremove -y; \
    apt-get clean; \
    \
    # Source maps are pure debugging weight in a shipped editor.
    find /opt/code-server -name '*.js.map' -delete 2>/dev/null || true; \
    find /opt/code-server -name '*.ts' -delete 2>/dev/null || true; \
    \
    # LLVM's static archives only. A blanket delete of *.a under /usr/lib was
    # tried and failed the build: clang links libgcc.a for compiler intrinsics
    # even when producing a dynamic executable, giving "cannot find -lgcc".
    # Anything under /usr/lib/gcc stays.
    find /usr/lib/llvm-* -name '*.a' -delete 2>/dev/null || true; \
    \
    # Python bytecode caches regenerate on demand.
    find / -name '*.pyc' -delete 2>/dev/null || true; \
    find / -name '__pycache__' -type d -exec rm -rf {} + 2>/dev/null || true; \
    \
    # Test suites shipped inside installed packages.
    find /usr/lib/python3* -type d -name 'test' -exec rm -rf {} + 2>/dev/null || true; \
    find /usr/lib/python3* -type d -name 'tests' -exec rm -rf {} + 2>/dev/null || true; \
    \
    rm -rf /usr/share/doc /usr/share/man /usr/share/locale /usr/share/info \
           /var/cache/apt /var/lib/apt/lists /tmp/* /root/.cache /root/.npm

# Prove the trims did not remove something load-bearing. Fails the build rather
# than shipping a rootfs whose compiler is gone.
RUN set -eux; \
    python3 --version; \
    clang --version | head -1; \
    code-server --version | head -1; \
    printf '#include <cstdio>\nint main(){std::printf("cpp ok\\n");return 0;}\n' > /tmp/t.cpp; \
    clang++ -std=c++20 /tmp/t.cpp -o /tmp/t; \
    /tmp/t; \
    printf 'print("py ok")\n' > /tmp/t.py; \
    python3 /tmp/t.py; \
    jupyter kernelspec list; \
    rm -f /tmp/t /tmp/t.cpp /tmp/t.py

# --- make the image runnable on Android -----------------------------------
#
# Strictly last. Patching rewrites every binary's interpreter to a path that
# exists on the phone and not in this container, so nothing above this line can
# run afterwards — which is exactly why the verification stage sits above it.
# ELF patching for Android happens in CI, after `docker export`, against the
# extracted tree — see tools/rootfs/patch-elf.sh. Doing it here would have the
# script rewrite bash, find and patchelf while it was still using them.

CMD ["/usr/local/bin/start-ide"]
