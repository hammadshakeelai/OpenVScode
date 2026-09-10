package com.openvscode.mobile;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.Map;

/**
 * Phase 1 proof for the self-bootstrapping rootfs (docs/SELF_BOOTSTRAP_PLAN.md).
 *
 * The question this answers is not "can we exec a downloaded binary" — that was
 * already measured, and the answer is only via /system/bin/linker64. It is the
 * harder follow-on: can a process we launch go on to exec its *own* children
 * out of the rootfs? If not, a rootfs is inert and the design has to fall back
 * to proot.
 *
 * Run as a controlled experiment. The same command runs twice, differing only
 * in whether libexechook.so is preloaded. The control must fail; if it does
 * not, the test is measuring nothing.
 */
final class BootstrapProbe {

    private static final String TAG = "PHASE1";

    private BootstrapProbe() { }

    static void run(final Context ctx) {
        new Thread(() -> {
            try {
                probe(ctx);
            } catch (Throwable t) {
                Log.e(TAG, "probe crashed", t);
            }
        }, "bootstrap-probe").start();
    }

    private static void probe(Context ctx) throws Exception {
        File rootfs = new File(ctx.getFilesDir(), "rootfs");
        File bin = new File(rootfs, "bin");
        if (!bin.exists() && !bin.mkdirs()) {
            Log.e(TAG, "could not create " + bin);
            return;
        }

        // toybox stands in for a real rootfs binary: it is already on the
        // device, and it picks its applet from argv[0], which doubles as a
        // check that the linker rewrite preserves argv correctly.
        File echo = new File(bin, "echo");
        copy(new File("/system/bin/toybox"), echo);
        if (!echo.setExecutable(true, true)) {
            Log.w(TAG, "chmod +x failed on " + echo);
        }

        String hook = ctx.getApplicationInfo().nativeLibraryDir + "/libexechook.so";
        Log.i(TAG, "hook exists=" + new File(hook).exists() + " path=" + hook);
        Log.i(TAG, "rootfs=" + rootfs.getAbsolutePath());

        // 1. Control. A plain system shell asked to run a rootfs binary, with
        //    no hook. This is what the OS does to an unmodified rootfs.
        Log.i(TAG, "--- 1. control: system shell -> rootfs binary, NO hook ---");
        runCase(new String[]{"/system/bin/sh", "-c", echo.getAbsolutePath() + " CONTROL_RAN"},
                null, null, rootfs);

        // 2. The same command with the shim preloaded. A pass here means the
        //    child's execve was rewritten through the linker for us.
        Log.i(TAG, "--- 2. same command WITH hook preloaded ---");
        runCase(new String[]{"/system/bin/sh", "-c", echo.getAbsolutePath() + " HOOKED_RAN"},
                hook, rootfs.getAbsolutePath(), rootfs);

        // 3. Two shells deep. LD_PRELOAD is inherited, so this shows the hook
        //    survives down a process tree rather than only the first child.
        Log.i(TAG, "--- 3. grandchild: shell -> shell -> rootfs binary ---");
        runCase(new String[]{"/system/bin/sh", "-c",
                        "/system/bin/sh -c '" + echo.getAbsolutePath() + " GRANDCHILD_RAN'"},
                hook, rootfs.getAbsolutePath(), rootfs);

        // 4. PATH resolution, which bionic does inside execvp() without going
        //    through the PLT — the reason the shim reimplements the search.
        Log.i(TAG, "--- 4. via PATH lookup rather than an absolute path ---");
        runCase(new String[]{"/system/bin/sh", "-c", "echo PATH_RAN"},
                hook, rootfs.getAbsolutePath(), rootfs);
    }

    private static void runCase(String[] cmd, String preload, String rootfsEnv, File cwd) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            pb.directory(cwd);

            Map<String, String> env = pb.environment();
            if (preload != null) {
                env.put("LD_PRELOAD", preload);
            } else {
                env.remove("LD_PRELOAD");
            }
            if (rootfsEnv != null) {
                env.put("OPENVSCODE_ROOTFS", rootfsEnv);
                // Put the rootfs first so case 4 resolves 'echo' to ours.
                env.put("PATH", rootfsEnv + "/bin:" + System.getenv("PATH"));
            } else {
                env.remove("OPENVSCODE_ROOTFS");
            }

            Process p = pb.start();
            StringBuilder out = new StringBuilder();
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = br.readLine()) != null) {
                out.append(line).append(" | ");
            }
            int rc = p.waitFor();
            Log.i(TAG, "    exit=" + rc + "  output=" + out.toString().trim());
        } catch (Exception e) {
            Log.i(TAG, "    FAILED TO START: " + e);
        }
    }

    private static void copy(File from, File to) throws Exception {
        InputStream in = new FileInputStream(from);
        OutputStream out = new FileOutputStream(to);
        try {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        } finally {
            in.close();
            out.close();
        }
    }
}
