# Builds the Linux rootfs the Android app downloads on first launch.
#
# Debian rather than Alpine on purpose. The bulk of the image is code-server,
# so Alpine's smaller base saves proportionally little, and musl costs real
# compatibility: code-server's official builds are glibc, and many Python
# wheels still have no musllinux variant. Compatibility is worth more than the
# ~45 MB the base would save, especially since this cannot be debugged from a
# developer machine once it is on a phone.
#
# Exported with `docker export`, so this only needs to produce a correct
# filesystem — nothing here is run as a container at runtime.
FROM debian:bookworm-slim

ENV DEBIAN_FRONTEND=noninteractive
ENV LANG=C.UTF-8

# One layer: `docker export` flattens anyway, and cleaning inside the same
# RUN is what actually keeps the exported tar small.
RUN set -eux; \
    apt-get update; \
    apt-get install -y --no-install-recommends \
        ca-certificates curl wget git xz-utils \
        python3 python3-pip python3-venv python3-dev \
        clang lld llvm make cmake pkg-config \
        nodejs npm \
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
    echo "fetching $url"; \
    curl -fsSL "$url" -o /tmp/code-server.tar.gz; \
    mkdir -p /opt/code-server; \
    tar -xzf /tmp/code-server.tar.gz -C /opt/code-server --strip-components=1; \
    rm /tmp/code-server.tar.gz; \
    ln -sf /opt/code-server/bin/code-server /usr/local/bin/code-server; \
    code-server --version

# Jupyter. `notebook` rather than the full JupyterLab: it is markedly smaller
# and the app renders it in a WebView on a phone screen either way.
RUN set -eux; \
    pip3 install --no-cache-dir --break-system-packages \
        notebook ipykernel; \
    python3 -m ipykernel install --name python3 --display-name "Python 3"; \
    pip3 install --no-cache-dir --break-system-packages jupyter-cpp-kernel || \
        echo "WARNING: jupyter-cpp-kernel unavailable; C++ still compiles from the terminal"

# Default code-server config: loopback only, no password. Nothing off-device can
# reach it, and the app cannot prompt for a password it has no way to know.
RUN set -eux; \
    mkdir -p /root/.config/code-server /root/workspace; \
    printf 'bind-addr: 127.0.0.1:8080\nauth: none\ncert: false\n' \
        > /root/.config/code-server/config.yaml

# Launcher used by the app. Kept inside the rootfs so the Android side only has
# to invoke one known path.
COPY start-ide.sh /usr/local/bin/start-ide
RUN chmod +x /usr/local/bin/start-ide

# Trim the largest remaining non-essentials. Measured, not guessed: without
# this the export runs a few hundred MB heavier.
RUN set -eux; \
    rm -rf /usr/share/doc /usr/share/man /usr/share/locale /usr/share/info; \
    rm -rf /var/cache/apt /var/lib/apt/lists /tmp/* /root/.cache; \
    find / -name '*.pyc' -delete 2>/dev/null || true; \
    find / -name '__pycache__' -type d -exec rm -rf {} + 2>/dev/null || true

CMD ["/usr/local/bin/start-ide"]
