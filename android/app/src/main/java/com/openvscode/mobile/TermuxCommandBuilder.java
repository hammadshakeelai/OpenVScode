package com.openvscode.mobile;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;

/** Builds the stdin script using APK assets only; never interpolates executable user input. */
final class TermuxCommandBuilder {
    // Android parcels strings as UTF-16. Leave ample room below Binder's shared limit.
    static final int MAX_SCRIPT_BYTES = 120 * 1024;
    static final int MAX_OUTPUT_CHARS = 12000;
    /** Printed by the probe script; its presence proves Termux ran our command. */
    static final String PROBE_MARKER = "openvscode-bridge-ok";

    private TermuxCommandBuilder() {}

    static String build(Map<String, byte[]> assets, String action, String token,
                        boolean notebooks) {
        return build(assets, action, token, notebooks, "00000000-0000-0000-0000-000000000000");
    }

    static String build(Map<String, byte[]> assets, String action, String token,
                        boolean notebooks, String requestId) {
        if (!"install".equals(action) && !"start".equals(action)) {
            throw new IllegalArgumentException("Unknown runtime action");
        }
        if (token == null || !token.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("Invalid status token");
        }
        requireRequestId(requestId);
        String[] requiredFiles = {"install.sh", "start.sh", "setup.sh", "scripts/runtime.sh",
                "scripts/status_server.py", "scripts/install_toolchain.sh",
                "scripts/install_extensions.sh", "scripts/install_jupyter_kernels.sh"};
        for (String path : requiredFiles) {
            if (assets == null || !assets.containsKey(path)) {
                throw new IllegalArgumentException("The APK is missing a bundled runtime file: " + path);
            }
        }
        StringBuilder script = new StringBuilder("set -euo pipefail\n"
                + "umask 077\n"
                + "runtime=\"$HOME/.local/share/openvscode/runtime\"\n"
                + "logdir=\"$HOME/.local/state/openvscode\"\n"
                + "mkdir -p -- \"$runtime\" \"$logdir\"\n"
                + "export OPENVSCODE_NONINTERACTIVE=1\n"
                + "export OPENVSCODE_REQUEST_ID='" + requestId + "'\n");
        for (Map.Entry<String, byte[]> asset : new TreeMap<>(assets).entrySet()) {
            String path = asset.getKey();
            validateAssetPath(path);
            byte[] bytes = asset.getValue();
            if (bytes == null || bytes.length > MAX_SCRIPT_BYTES) {
                throw new IllegalArgumentException("Bundled runtime asset is too large");
            }
            int separator = path.lastIndexOf('/');
            if (separator >= 0) {
                script.append("mkdir -p -- \"$runtime/\"'")
                        .append(path, 0, separator).append("'\n");
            }
            script.append("printf '%s' '")
                    .append(Base64.getEncoder().encodeToString(bytes))
                    .append("' | base64 -d > \"$runtime/\"'").append(path).append("'\".new-$$\"\n")
                    .append("mv -f -- \"$runtime/\"'").append(path).append("'\".new-$$\" \"$runtime/\"'")
                    .append(path).append("'\n");
            if (path.endsWith(".sh")) {
                script.append("chmod 700 -- \"$runtime/\"'").append(path).append("'\n");
            }
            ensureBounded(script);
        }
        script.append("set +e\n")
                .append("bash \"$runtime/").append(action).append(".sh\" --status-token '")
                .append(token).append("'");
        if (notebooks && "install".equals(action)) script.append(" --with-notebooks");
        // Never pass inherited stdin to package managers, or output pipes to detached processes.
        script.append(" < /dev/null > \"$logdir/bridge.log\" 2>&1\n")
                .append("result=$?\n")
                .append("tail -c 12000 -- \"$logdir/bridge.log\"\n")
                .append("exit \"$result\"\n");
        ensureBounded(script);
        return script.toString();
    }

    /**
     * A one-second round trip that proves the whole bridge before a long download:
     * Termux accepted the command, allow-external-apps is set, and bash answers.
     */
    static String buildProbe(String requestId) {
        requireRequestId(requestId);
        return "printf 'request %s\\n' '" + requestId + "'\n"
                + "printf 'arch %s\\n' \"$(uname -m)\"\n"
                + "printf 'free-kb %s\\n' \"$(df -Pk \"$HOME\" 2>/dev/null | awk 'NR == 2 { print $4 }')\"\n"
                + "command -v code-server >/dev/null 2>&1 && printf 'editor installed\\n'\n"
                + "printf '%s\\n' '" + PROBE_MARKER + "'\n";
    }

    private static void requireRequestId(String requestId) {
        if (requestId == null || !requestId.matches(
                "[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")) {
            throw new IllegalArgumentException("Invalid runtime request ID");
        }
    }

    static void validateAssetPath(String path) {
        if (path == null || path.isEmpty() || path.length() > 240
                || !path.matches("[A-Za-z0-9_./-]+") || path.startsWith("/")
                || path.endsWith("/") || path.contains("//")) {
            throw new IllegalArgumentException("Invalid bundled runtime path");
        }
        for (String part : path.split("/")) {
            if (".".equals(part) || "..".equals(part)) {
                throw new IllegalArgumentException("Bundled runtime path leaves its directory");
            }
        }
    }

    static String safeOutput(String value, String token) {
        if (value == null) return "";
        String cleaned = value.replaceAll("\\x1B\\[[0-?]*[ -/]*[@-~]", "")
                .replaceAll("[\\p{Cc}&&[^\\n\\t]]", "");
        if (token != null && !token.isEmpty()) cleaned = cleaned.replace(token, "[private token]");
        return cleaned.length() > MAX_OUTPUT_CHARS
                ? cleaned.substring(cleaned.length() - MAX_OUTPUT_CHARS) : cleaned;
    }

    private static void ensureBounded(StringBuilder script) {
        if (script.toString().getBytes(StandardCharsets.UTF_8).length > MAX_SCRIPT_BYTES) {
            throw new IllegalArgumentException("Bundled runtime exceeds Android's command size limit");
        }
    }
}
