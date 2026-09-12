package com.openvscode.mobile;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Explicit Termux RUN_COMMAND integration with durable, correlated command results.
 * API contract: https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent
 * Call install/start while an Activity is visible; Android can reject background service starts.
 */
public final class TermuxBridge {
    public static final String RUN_PERMISSION = "com.termux.permission.RUN_COMMAND";
    public static final String STATUS_URL = "http://127.0.0.1:8766/status";
    private static final String TERMUX_PACKAGE = "com.termux";
    private static final String PREFS_NAME = "openvscode_termux";
    private static final String RESULT_ACTION = "com.openvscode.mobile.TERMUX_RESULT";
    private static final String PREFIX = "com.termux.RUN_COMMAND_";
    private static final String RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService";
    static final String PROBE_ACTION = "probe";

    private TermuxBridge() {}

    public static boolean isInstalled(Context context) {
        try {
            ApplicationInfo app = context.getPackageManager().getApplicationInfo(TERMUX_PACKAGE, 0);
            return app.enabled;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /**
     * The Google Play build of Termux ships without RunCommandService and never
     * declares the permission, so no app can drive it. Detect that here instead
     * of asking Android for a permission it will silently refuse.
     */
    public static boolean canRunCommands(Context context) {
        PackageManager packages = context.getPackageManager();
        try {
            packages.getPermissionInfo(RUN_PERMISSION, 0);
            packages.getServiceInfo(new ComponentName(TERMUX_PACKAGE, RUN_COMMAND_SERVICE), 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    public static boolean hasRunPermission(Context context) {
        return context.checkSelfPermission(RUN_PERMISSION) == PackageManager.PERMISSION_GRANTED;
    }

    public static boolean openTermux(Context context) {
        Intent intent = context.getPackageManager().getLaunchIntentForPackage(TERMUX_PACKAGE);
        if (intent == null) return false;
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(intent);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** The single, reviewable command the user pastes into Termux once. */
    public static String enableExternalAppsCommand() {
        return "mkdir -p ~/.termux && "
                + "touch ~/.termux/termux.properties && "
                + "sed -i '/^[[:space:]]*allow-external-apps[[:space:]]*=/d' ~/.termux/termux.properties && "
                + "printf '\\nallow-external-apps=true\\n' >> ~/.termux/termux.properties && "
                + "termux-reload-settings && "
                + "printf '\\nReady! Return to OpenVScode and tap Install.\\n'";
    }

    public static synchronized String statusToken(Context context) {
        SharedPreferences prefs = prefs(context);
        String existing = prefs.getString("status_token", "");
        if (existing != null && existing.matches("[a-f0-9]{64}")) return existing;
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        StringBuilder token = new StringBuilder(64);
        for (byte value : random) token.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        String result = token.toString();
        prefs.edit().putString("status_token", result).commit();
        return result;
    }

    public static boolean install(Context context, boolean notebooks) {
        return dispatch(context, "install", notebooks);
    }

    public static boolean start(Context context) {
        return dispatch(context, "start", false);
    }

    /** Verifies the link in about a second, before a download the user must wait for. */
    public static boolean probe(Context context) {
        return dispatch(context, PROBE_ACTION, false);
    }

    public static boolean isBridgeVerified(Context context) {
        return prefs(context).getBoolean("bridge_verified", false);
    }

    /** Sends the user back through the link check after Termux is reinstalled or reset. */
    public static void forgetVerification(Context context) {
        prefs(context).edit().putBoolean("bridge_verified", false).commit();
    }

    public static boolean hasRuntime(Context context) {
        return prefs(context).getBoolean("runtime_installed", false);
    }

    public static synchronized State readState(Context context) {
        SharedPreferences prefs = prefs(context);
        return new State(prefs.getString("action", ""), prefs.getBoolean("running", false),
                prefs.getBoolean("success", false), prefs.getString("message", ""),
                prefs.getString("output", ""), prefs.getLong("started_at", 0),
                prefs.getLong("finished_at", 0), prefs.getString("request_id", ""));
    }

    /** Call only after the authenticated status endpoint and editor health check report ready. */
    public static synchronized void recordReady(Context context) {
        prefs(context).edit().putBoolean("runtime_installed", true)
                .putBoolean("running", false).putBoolean("success", true)
                .putString("message", "Your workspace is ready")
                .putLong("finished_at", System.currentTimeMillis()).commit();
    }

    /** A late HTTP response must never complete an operation started after that request. */
    public static synchronized boolean recordReady(Context context, String expectedRequestId) {
        if (expectedRequestId == null || !expectedRequestId.equals(readState(context).requestId)) return false;
        recordReady(context);
        return true;
    }

    /** Authenticated progress can report an interrupted process even if Android lost its callback. */
    public static synchronized boolean recordFailure(Context context, String expectedRequestId,
                                                     String message, String output) {
        if (expectedRequestId == null || !expectedRequestId.equals(readState(context).requestId)) return false;
        fail(context, message, output);
        return true;
    }

    /** Lets an explicit Retry replace an orphaned callback after Android stops Termux. */
    public static synchronized void abandonPending(Context context) {
        prefs(context).edit().putString("request_id", "")
                .putBoolean("running", false).putBoolean("success", false)
                .putString("message", "Ready to retry. Completed installation steps will be reused.")
                .putLong("finished_at", System.currentTimeMillis()).commit();
    }

    public static void clearPending(Context context) {
        abandonPending(context);
    }

    private static synchronized boolean dispatch(Context context, String action, boolean notebooks) {
        Context app = context.getApplicationContext();
        if (readState(app).running) return false;
        String requestId = UUID.randomUUID().toString();
        SharedPreferences.Editor started = prefs(app).edit()
                .putString("request_id", requestId).putString("action", action)
                .putBoolean("running", true).putBoolean("success", false)
                .putString("message", messageFor(action))
                .putString("output", "").putLong("started_at", System.currentTimeMillis())
                .putLong("finished_at", 0);
        // A fresh check must not inherit the verdict of the previous one.
        if (PROBE_ACTION.equals(action)) started.putBoolean("bridge_verified", false);
        started.commit();
        if (!isInstalled(app)) {
            fail(app, "Install and open Termux first, then return here.", "");
            return false;
        }
        if (!hasRunPermission(app)) {
            fail(app, "Allow OpenVScode to run commands in Termux, then try again.", "");
            return false;
        }
        PendingIntent callback = null;
        try {
            String command;
            if (PROBE_ACTION.equals(action)) {
                command = TermuxCommandBuilder.buildProbe(requestId);
            } else {
                Map<String, byte[]> assets = new LinkedHashMap<>();
                readAssets(app.getAssets(), "runtime", "", assets, new int[]{0});
                command = TermuxCommandBuilder.build(assets, action, statusToken(app), notebooks, requestId);
            }
            Intent resultIntent = new Intent(app, TermuxResultReceiver.class)
                    .setAction(RESULT_ACTION)
                    .setData(Uri.parse("openvscode://termux-result/" + requestId));
            int flags = PendingIntent.FLAG_ONE_SHOT;
            // Immutable PendingIntents discard Termux's result extras.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) flags |= PendingIntent.FLAG_MUTABLE;
            callback = PendingIntent.getBroadcast(app, 0, resultIntent, flags);
            Intent commandIntent = new Intent("com.termux.RUN_COMMAND")
                    .setComponent(new ComponentName(TERMUX_PACKAGE, RUN_COMMAND_SERVICE))
                    .putExtra(PREFIX + "PATH", "/data/data/com.termux/files/usr/bin/bash")
                    .putExtra(PREFIX + "ARGUMENTS", new String[]{"-s"})
                    .putExtra(PREFIX + "STDIN", command)
                    .putExtra(PREFIX + "WORKDIR", "/data/data/com.termux/files/home")
                    .putExtra(PREFIX + "BACKGROUND", true)
                    .putExtra(PREFIX + "BACKGROUND_CUSTOM_LOG_LEVEL", "off")
                    .putExtra(PREFIX + "COMMAND_LABEL", labelFor(action))
                    .putExtra(PREFIX + "COMMAND_DESCRIPTION", "Runs the runtime bundled with your OpenVScode APK.")
                    .putExtra(PREFIX + "PENDING_INTENT", callback);
            ComponentName service = app.startService(commandIntent);
            if (service == null) throw new IllegalStateException("Termux command service is unavailable");
            return true;
        } catch (SecurityException e) {
            if (callback != null) callback.cancel();
            fail(app, "Termux access was denied. Grant the Termux permission and complete the one-time setup command.", e.getMessage());
        } catch (IllegalStateException e) {
            if (callback != null) callback.cancel();
            fail(app, "Open Termux once, return to OpenVScode, and retry. Android may have stopped its service.", e.getMessage());
        } catch (IOException | IllegalArgumentException e) {
            if (callback != null) callback.cancel();
            fail(app, "Could not prepare the bundled installer. Reinstall the latest OpenVScode APK.", e.getMessage());
        } catch (RuntimeException e) {
            if (callback != null) callback.cancel();
            fail(app, "Could not reach Termux. Open Termux, return here, and retry.", e.getMessage());
        }
        return false;
    }

    static synchronized void receiveResult(Context context, Intent intent) {
        if (intent == null || !RESULT_ACTION.equals(intent.getAction()) || intent.getData() == null) return;
        String requestId = intent.getData().getLastPathSegment();
        State state = readState(context);
        if (requestId == null || !requestId.equals(state.requestId)) return;
        Bundle result = intent.getBundleExtra("result");
        if (result == null) {
            fail(context, "Termux returned no result. Open Termux to check its setup, then retry.", "");
            return;
        }
        int internalError = result.getInt("err", Activity.RESULT_OK);
        int exitCode = result.getInt("exitCode", -1);
        String error = result.getString("errmsg", "");
        String output = TermuxCommandBuilder.safeOutput(result.getString("stdout", "")
                + "\n" + result.getString("stderr", "") + "\n" + error, statusToken(context)).trim();
        boolean success = internalError == Activity.RESULT_OK && exitCode == 0;
        if (!success) {
            String message = "Termux could not finish. Check the details below and retry; completed steps are kept.";
            String lower = (error + "\n" + output).toLowerCase(Locale.ROOT);
            if (lower.contains("allow-external-apps") || lower.contains("allow external apps")) {
                message = "Complete the one-time setup command in Termux, then return here and retry.";
            } else if (lower.contains("permission denied") || lower.contains("run_command permission")) {
                message = "Termux permission is missing. Allow command access in Android settings, then retry.";
            } else if (lower.contains("no such file") && lower.contains("/bin/bash")) {
                message = "Open Termux and wait for its first-time installation to finish, then retry.";
            }
            fail(context, message, output + "\nExit code: " + exitCode);
            return;
        }
        boolean probed = PROBE_ACTION.equals(state.action);
        // Termux reports success for a command it never ran when its own setup is
        // incomplete, so the marker — not the exit code alone — proves the link.
        if (probed && !output.contains(TermuxCommandBuilder.PROBE_MARKER)) {
            fail(context, "Termux did not run the command. Finish the one-time setup line in Termux, then try again.", output);
            return;
        }
        SharedPreferences.Editor finished = prefs(context).edit()
                .putBoolean("running", false).putBoolean("success", true)
                .putBoolean("bridge_verified", true).putString("output", output)
                .putLong("finished_at", System.currentTimeMillis());
        if (probed) {
            finished.putString("message", "Termux is connected.");
            // A check proves only the link; an editor may survive from an earlier run.
            if (output.contains("editor installed")) finished.putBoolean("runtime_installed", true);
        } else {
            finished.putBoolean("runtime_installed", true).putString("message", "Your workspace is ready");
        }
        finished.commit();
    }

    private static String messageFor(String action) {
        if (PROBE_ACTION.equals(action)) return "Checking the link to Termux…";
        if ("install".equals(action)) {
            return "Preparing the tools in Termux. The first download can take a few minutes.";
        }
        return "Starting your workspace in Termux…";
    }

    private static String labelFor(String action) {
        if (PROBE_ACTION.equals(action)) return "Check the OpenVScode link";
        return "install".equals(action) ? "Set up OpenVScode" : "Start OpenVScode";
    }

    private static void fail(Context context, String message, String output) {
        prefs(context).edit().putBoolean("running", false).putBoolean("success", false)
                .putString("message", message)
                .putString("output", TermuxCommandBuilder.safeOutput(output, statusToken(context)))
                .putLong("finished_at", System.currentTimeMillis()).commit();
    }

    private static void readAssets(AssetManager manager, String assetPath, String relativePath,
                                   Map<String, byte[]> target, int[] totalBytes) throws IOException {
        String[] children = manager.list(assetPath);
        if (children != null && children.length > 0) {
            for (String child : children) {
                readAssets(manager, assetPath + "/" + child,
                        relativePath.isEmpty() ? child : relativePath + "/" + child, target, totalBytes);
            }
            return;
        }
        TermuxCommandBuilder.validateAssetPath(relativePath);
        try (InputStream input = manager.open(assetPath);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) {
                totalBytes[0] += count;
                if (totalBytes[0] > TermuxCommandBuilder.MAX_SCRIPT_BYTES) {
                    throw new IOException("Bundled runtime is too large for Android command transport");
                }
                output.write(buffer, 0, count);
            }
            target.put(relativePath, output.toByteArray());
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static final class State {
        public final String action;
        public final boolean running;
        public final boolean success;
        public final String message;
        public final String output;
        public final long startedAt;
        public final long finishedAt;
        public final String requestId;

        private State(String action, boolean running, boolean success, String message, String output,
                      long startedAt, long finishedAt, String requestId) {
            this.action = action;
            this.running = running;
            this.success = success;
            this.message = message;
            this.output = output;
            this.startedAt = startedAt;
            this.finishedAt = finishedAt;
            this.requestId = requestId;
        }
    }
}
