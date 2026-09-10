package com.openvscode.mobile;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * Starts the IDE inside the installed rootfs.
 *
 * Nothing here invokes a dynamic loader explicitly. The image is patched at
 * build time so every binary's ELF interpreter and every script's shebang point
 * at absolute paths inside the install directory, which means these binaries
 * are executed the ordinary way. That matters for more than tidiness: going
 * through the loader makes /proc/self/exe report the loader instead of the
 * program, and code-server spawns a child from that path and dies. See
 * docs/SELF_BOOTSTRAP_PLAN.md.
 */
final class RootfsLauncher {

    private static final String TAG = "RootfsLauncher";

    /** Where the image expects to live. Must match ROOTFS_INSTALL_PATH in CI. */
    private static final String EXPECTED_PATH =
            "/data/data/com.openvscode.mobile.debug/files/rootfs";

    private static Process running;

    private RootfsLauncher() { }

    /**
     * The install path is baked into every patched binary, so a rootfs unpacked
     * anywhere else simply will not execute. Checking here turns that into a
     * clear message instead of a pile of ENOENTs.
     */
    static String pathProblem(Context ctx) {
        File dir = RootfsInstaller.rootfsDir(ctx);
        String actual = dir.getAbsolutePath();
        if (actual.equals(EXPECTED_PATH)) {
            return null;
        }
        // /data/user/0/<pkg> and /data/data/<pkg> are the same directory; the
        // latter is a symlink to the former, and the kernel resolves it.
        String normalised = actual.replace("/data/user/0/", "/data/data/");
        if (normalised.equals(EXPECTED_PATH)) {
            return null;
        }
        return "This rootfs was built for " + EXPECTED_PATH
                + " but the app stores files at " + actual + ".";
    }

    static boolean isRunning() {
        if (running == null) {
            return false;
        }
        try {
            running.exitValue();
            return false;   // exited
        } catch (IllegalThreadStateException alive) {
            return true;
        }
    }

    /**
     * Launches code-server and returns immediately. The caller polls
     * 127.0.0.1:8080 as it already does for any other server, so no new
     * readiness protocol is needed.
     */
    static synchronized void start(Context ctx) throws Exception {
        if (isRunning()) {
            Log.i(TAG, "already running");
            return;
        }

        File rootfs = RootfsInstaller.rootfsDir(ctx);
        File launcher = new File(rootfs, "usr/local/bin/start-ide");
        if (!launcher.exists()) {
            throw new IllegalStateException("start-ide missing from the rootfs at " + launcher);
        }
        if (!launcher.canExecute() && !launcher.setExecutable(true, false)) {
            Log.w(TAG, "could not mark start-ide executable");
        }

        String root = rootfs.getAbsolutePath();
        File home = new File(rootfs, "root");
        if (!home.isDirectory() && !home.mkdirs()) {
            Log.w(TAG, "could not create " + home);
        }

        ProcessBuilder pb = new ProcessBuilder(launcher.getAbsolutePath());
        pb.directory(home);
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(new File(home, "ide.log")));

        Map<String, String> env = pb.environment();
        env.clear();     // Android's own environment means nothing in here.
        env.putAll(baseEnvironment(root, home.getAbsolutePath()));

        Log.i(TAG, "starting " + launcher);
        running = pb.start();
    }

    /** The environment a Debian userland expects, pointed at the install path. */
    static Map<String, String> baseEnvironment(String root, String home) {
        Map<String, String> env = new HashMap<>();
        env.put("HOME", home);
        env.put("PATH", root + "/usr/local/sbin:" + root + "/usr/local/bin:"
                + root + "/usr/sbin:" + root + "/usr/bin:"
                + root + "/sbin:" + root + "/bin");
        // The image is patched to fix each executable's ELF interpreter, but
        // deliberately NOT its RPATH: rewriting RPATH across every shared
        // library left python3.11, node and clang segfaulting before the loader
        // produced a single line of output. Library lookup lives here instead,
        // and children inherit it for free.
        String triplet = is64BitArm() ? "aarch64-linux-gnu" : "x86_64-linux-gnu";
        env.put("LD_LIBRARY_PATH",
                root + "/lib/" + triplet + ":"
                        + root + "/usr/lib/" + triplet + ":"
                        + root + "/lib:"
                        + root + "/usr/lib:"
                        + root + "/usr/local/lib:"
                        + root + "/usr/lib/llvm-14/lib:"
                        + root + "/opt/code-server/lib");
        env.put("TMPDIR", root + "/tmp");
        env.put("SHELL", root + "/bin/bash");
        env.put("USER", "root");
        env.put("LANG", "C.UTF-8");
        env.put("TERM", "xterm-256color");
        // Python locates its standard library relative to its own executable,
        // which the patched binaries report correctly, so PYTHONHOME is
        // deliberately not set — setting it wrongly breaks more than it fixes.
        env.put("OPENVSCODE_ROOTFS", root);
        env.put("OPENVSCODE_PORT", "8080");
        env.put("OPENVSCODE_WORKSPACE", home + "/workspace");
        return env;
    }

    /** Which rootfs image this device needs; decides the library triplet. */
    private static boolean is64BitArm() {
        for (String abi : android.os.Build.SUPPORTED_ABIS) {
            if ("arm64-v8a".equals(abi)) {
                return true;
            }
            if ("x86_64".equals(abi)) {
                return false;
            }
        }
        return true;
    }

    static synchronized void stop() {
        if (running != null) {
            Log.i(TAG, "stopping the IDE process");
            running.destroy();
            running = null;
        }
    }

    /** Last lines of the IDE log, for showing a real error instead of a spinner. */
    static String tailLog(Context ctx, int lines) {
        File log = new File(RootfsInstaller.rootfsDir(ctx), "root/ide.log");
        if (!log.isFile()) {
            return "(no log yet)";
        }
        try {
            java.util.List<String> all = new java.util.ArrayList<>();
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(log));
            String line;
            while ((line = r.readLine()) != null) {
                all.add(line);
                if (all.size() > 400) {
                    all.remove(0);
                }
            }
            r.close();
            StringBuilder sb = new StringBuilder();
            for (int i = Math.max(0, all.size() - lines); i < all.size(); i++) {
                sb.append(all.get(i)).append('\n');
            }
            return sb.toString();
        } catch (Exception e) {
            return "(could not read the log: " + e + ")";
        }
    }
}
