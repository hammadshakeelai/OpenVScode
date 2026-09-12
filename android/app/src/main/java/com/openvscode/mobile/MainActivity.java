package com.openvscode.mobile;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.webkit.*;
import android.widget.*;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.*;
import org.json.*;
import java.io.*;
import java.net.*;
import java.util.List;
import java.util.concurrent.*;

/** Native setup and session shell. Termux owns all downloaded executables. */
public class MainActivity extends AppCompatActivity {
    public static final String DEFAULT_SERVER_URL = "http://127.0.0.1:8080";
    private static final int BLUE = Color.rgb(122,199,255), TEXT = Color.rgb(188,202,219);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private SharedPreferences prefs;
    private View setup, editor;
    private LinearLayout steps, detail, keys;
    private TextView title, subtitle, caption, footnote;
    private Button primary, secondary, remote;
    private ProgressBar progress;
    private WebView webView;
    private ValueCallback<Uri[]> fileCallback;
    private boolean foreground, checking, localReady, editorVisible, loadingPage, pageFailed;
    private boolean ctrl, alt, notebooks, connectionError;
    private int generation;
    private String screen = "", notice = "", logs = "", taskSignature = "", serverUrl = DEFAULT_SERVER_URL;
    private JSONObject runtimeStatus;
    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (!foreground) return;
            checkRuntime();
            handler.postDelayed(this, 2500);
        }
    };
    private final ActivityResultLauncher<String> permission = registerForActivityResult(
        new ActivityResultContracts.RequestPermission(), granted -> {
            // Prove the link immediately: a granted permission alone does not mean
            // Termux will accept commands.
            if (granted) { notice = ""; startProbe(); return; }
            notice = "Android has not granted access. Enable “Run commands in Termux” under App permissions.";
            render(true);
        });
    private final ActivityResultLauncher<String> notifications = registerForActivityResult(
        new ActivityResultContracts.RequestPermission(), granted -> {});
    private final ActivityResultLauncher<Intent> files = registerForActivityResult(
        new ActivityResultContracts.StartActivityForResult(), result -> {
            if (fileCallback != null) {
                fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result.getResultCode(), result.getData()));
                fileCallback = null;
            }
        });

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences("openvscode_prefs", MODE_PRIVATE);
        if (TermuxBridge.hasRuntime(this)) prefs.edit().putBoolean("core_installed", true).apply();
        setup = findViewById(R.id.setupScroll); editor = findViewById(R.id.editorContainer);
        steps = findViewById(R.id.stepsContainer); detail = findViewById(R.id.detailContainer);
        keys = findViewById(R.id.keysContainer); title = findViewById(R.id.statusTitle);
        subtitle = findViewById(R.id.statusSubtitle); caption = findViewById(R.id.progressCaption);
        footnote = findViewById(R.id.setupFootnote); primary = findViewById(R.id.primaryButton);
        secondary = findViewById(R.id.secondaryButton); remote = findViewById(R.id.remoteButton);
        progress = findViewById(R.id.installProgress);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.appRoot), (view, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            view.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, insets.getInsets(WindowInsetsCompat.Type.ime()).bottom));
            return insets;
        });
        createWebView(); buildKeybar();
        remote.setOnClickListener(v -> showRemoteDialog());
        findViewById(R.id.helpButton).setOnClickListener(v -> showHelp());
        findViewById(R.id.sessionButton).setOnClickListener(v -> showSession());
        render(true);
        if (state != null && state.getBoolean("editorVisible")) connect(state.getString("editorUrl", DEFAULT_SERVER_URL));
        else if ("remote".equals(prefs.getString("mode", "local"))) connect(prefs.getString("server_url", DEFAULT_SERVER_URL));
        else if (prefs.getBoolean("core_installed", false) && TermuxBridge.canRunCommands(this)
                && TermuxBridge.hasRunPermission(this) && TermuxBridge.isBridgeVerified(this)
                && !TermuxBridge.readState(this).running) runRuntime(false);
    }
    @Override protected void onStart() {
        super.onStart(); foreground = true; render(true); handler.post(poll);
    }
    @Override protected void onStop() {
        foreground = false; handler.removeCallbacks(poll); super.onStop();
    }
    @Override protected void onSaveInstanceState(Bundle out) {
        out.putBoolean("editorVisible", editorVisible); out.putString("editorUrl", serverUrl);
        super.onSaveInstanceState(out);
    }
    @Override protected void onDestroy() {
        foreground = false; generation++; handler.removeCallbacksAndMessages(null); network.shutdownNow();
        if (fileCallback != null) fileCallback.onReceiveValue(null);
        if (webView != null) { ((ViewGroup) webView.getParent()).removeView(webView); webView.destroy(); }
        super.onDestroy();
    }

    private void render(boolean force) {
        if (prefs == null || editorVisible || loadingPage || connectionError) return;
        TermuxBridge.State task = TermuxBridge.readState(this);
        // Repaint whenever Termux reports something new, even within one screen.
        String signature = task.requestId + task.running + task.success + task.message;
        if (!signature.equals(taskSignature)) { taskSignature = signature; force = true; }
        boolean checkingLink = task.running && TermuxBridge.PROBE_ACTION.equals(task.action);
        String next;
        if (!prefs.getBoolean("setup_started", false)) next = "welcome";
        else if (!TermuxBridge.isInstalled(this)) next = "termux";
        else if (!TermuxBridge.canRunCommands(this)) next = "termux_build";
        else if (checkingLink || !TermuxBridge.hasRunPermission(this) || !TermuxBridge.isBridgeVerified(this)) next = "link";
        else if (task.running) next = "installing";
        else if (localReady) next = "ready";
        else if (!task.success && !task.action.isEmpty()) next = "error";
        else next = "install";
        setup.setVisibility(View.VISIBLE); editor.setVisibility(View.GONE);
        if (!force && next.equals(screen)) { updateProgress(task); return; }
        screen = next; detail.removeAllViews(); secondary.setVisibility(View.GONE);
        primary.setEnabled(true); remote.setEnabled(!task.running);
        progress.setVisibility(View.GONE); caption.setVisibility(View.GONE);
        footnote.setText("One-time setup · No root needed");
        int current = next.equals("welcome") || next.startsWith("termux") ? 0 : next.equals("link") ? 1 : next.equals("ready") ? 3 : 2;
        buildSteps(current);
        switch (next) {
            case "welcome":
                title.setText("Let’s get you\ncoding.");
                subtitle.setText("VS Code, a terminal, Python and C++ on your Android. We’ll walk you through the setup.");
                note("Runs locally with Termux. Allow about 1 GB of free space and 10–30 minutes for the first download.");
                action("Get started", () -> { prefs.edit().putBoolean("setup_started", true).putString("mode", "local").apply(); render(true); });
                break;
            case "termux":
                title.setText("First, get Termux.");
                subtitle.setText("Termux runs your development tools on this phone. Install it, open it once, then come back here.");
                note("Use the F-Droid build for this setup. Android may ask you to allow installation from your browser.");
                action("Download Termux", () -> openUrl("https://f-droid.org/en/packages/com.termux/"));
                secondary("I’ve installed Termux", () -> {
                    notice = TermuxBridge.isInstalled(this) ? "" : "Termux is not installed yet. Finish installing it, then open it once.";
                    render(true);
                });
                break;
            case "link":
                title.setText("Connect the two apps.");
                subtitle.setText("One small step in Termux lets OpenVScode install and start your editor for you.");
                note("1. Copy the line below.\n2. Open Termux, paste it, and press Enter.\n3. Come back and tap Check the link.");
                TextView command = note(TermuxBridge.enableExternalAppsCommand());
                command.setTypeface(Typeface.MONOSPACE); command.setTextSize(11); command.setTextIsSelectable(true);
                if (checkingLink) {
                    progress.setVisibility(View.VISIBLE); progress.setIndeterminate(true);
                    caption.setVisibility(View.VISIBLE); caption.setText("Running a one-second test command in Termux…");
                    primary.setEnabled(false); action("Checking the link…", () -> {});
                } else {
                    action("Copy line & open Termux", () -> {
                        copy("Termux setup", TermuxBridge.enableExternalAppsCommand());
                        if (!TermuxBridge.openTermux(this)) toast("Open Termux from your app drawer.");
                    });
                    secondary("I’ve run it · Check the link", () -> {
                        notice = "";
                        if (!TermuxBridge.hasRunPermission(this)) permission.launch(TermuxBridge.RUN_PERMISSION);
                        else startProbe();
                    });
                }
                // The check reports exactly why Termux refused, instead of failing later.
                if (!task.success && !task.message.isEmpty() && TermuxBridge.PROBE_ACTION.equals(task.action)) note(task.message);
                if (!notice.isEmpty()) smallButton("Open Android app permissions", () -> openAppSettings(getPackageName()));
                break;
            case "termux_build":
                title.setText("This Termux can’t\nbe automated.");
                subtitle.setText("The Play Store build of Termux has no command bridge, so no app can set it up for you.");
                note("Install Termux from F-Droid or GitHub instead. Uninstalling the Play Store build deletes its files, so copy anything you need out of it first.");
                action("Get Termux from F-Droid", () -> openUrl("https://f-droid.org/en/packages/com.termux/"));
                secondary("I’ve switched builds", () -> render(true));
                smallButton("Open Termux app info", () -> openAppSettings("com.termux"));
                break;
            case "install":
                boolean installed = prefs.getBoolean("core_installed", false);
                title.setText(installed ? "Welcome back." : "Make room for ideas.");
                subtitle.setText(installed
                    ? "Start your local editor and pick up where you left off. Your projects stay in Termux."
                    : "We’ll install the editor, Python and a C++ compiler. Your projects live on your phone.");
                if (!installed) {
                    CheckBox check = new CheckBox(this); check.setText("Also install Jupyter notebooks (takes longer)");
                    check.setTextColor(TEXT); check.setTextSize(14); check.setChecked(notebooks);
                    check.setOnCheckedChangeListener((v, checked) -> notebooks = checked); detail.addView(check);
                    note("Keep Termux running during setup. If a download stops, retrying keeps completed work and your files.");
                }
                action(installed ? "Start editor" : "Install my workspace", () -> runRuntime(!installed));
                // Notebooks used to be offered only before the first install, so
                // anyone who skipped them had no way to add them afterwards.
                if (installed && !prefs.getBoolean("notebooks_installed", false)) {
                    smallButton("Add Jupyter notebooks", () -> { notebooks = true; runRuntime(true); });
                }
                break;
            case "installing":
                title.setText(task.action.equals("start") ? "Waking your workspace." : "Your workspace is\ntaking shape.");
                subtitle.setText("Termux is doing the work. You can leave this screen and return to check progress.");
                action("Open Termux", () -> TermuxBridge.openTermux(this));
                secondary("View installation log", this::showLogs);
                smallButton("Installation seems stuck", this::showRecovery);
                footnote.setText("Keep Termux open · Wi-Fi recommended");
                break;
            case "ready":
                title.setText("You’re ready to code.");
                subtitle.setText("Your local editor is responding. Open your workspace and make something.");
                note("Your files: ~/OpenVScode_Workspace\nUse the editor’s Terminal menu to run Python, compile C++, or install more tools.");
                action("Open editor", () -> connect(DEFAULT_SERVER_URL));
                secondary("View setup details", this::showLogs);
                // The install screen is unreachable once the editor answers, so the
                // one chance to add notebooks has to live here too.
                if (!prefs.getBoolean("notebooks_installed", false)) {
                    smallButton("Add Jupyter notebooks", () -> { notebooks = true; runRuntime(true); });
                }
                footnote.setText("Local workspace · Available offline after setup");
                break;
            default:
                title.setText("Let’s get this unstuck.");
                subtitle.setText(task.message.isEmpty() ? "The editor could not start. Your projects and completed downloads are safe." : task.message);
                note("Check the log for the failed step. Keep Termux open and your internet connected, then retry.");
                action("Retry setup", () -> runRuntime(!prefs.getBoolean("core_installed", false)));
                secondary("View installation log", this::showLogs);
                smallButton("Reconnect Termux", () -> { TermuxBridge.forgetVerification(this); notice = ""; render(true); });
        }
        if (!notice.isEmpty()) note(notice);
        updateProgress(task);
    }

    private void updateProgress(TermuxBridge.State task) {
        if (!screen.equals("installing")) return;
        progress.setVisibility(View.VISIBLE); caption.setVisibility(View.VISIBLE);
        long elapsed = Math.max(0, (System.currentTimeMillis() - task.startedAt) / 1000);
        String message = "Waiting for Termux to start. The first package download may take a few minutes.";
        boolean current = runtimeStatus != null && task.requestId.equals(runtimeStatus.optString("requestId"));
        progress.setIndeterminate(!current);
        if (current) {
            progress.setProgress(Math.max(0, Math.min(100, runtimeStatus.optInt("progress"))));
            message = runtimeStatus.optString("message", message);
        }
        caption.setText(message + "\n" + elapsed / 60 + "m " + elapsed % 60 + "s elapsed");
    }
    /** One second of certainty before a download the user has to wait through. */
    private void startProbe() {
        if (TermuxBridge.readState(this).running) { render(true); return; }
        notice = ""; TermuxBridge.probe(this); render(true);
    }
    private void runRuntime(boolean install) {
        if (TermuxBridge.readState(this).running) { render(true); return; }
        generation++; localReady = false; runtimeStatus = null; notice = "";
        SharedPreferences.Editor request = prefs.edit().putString("mode", "local").putBoolean("setup_started", true);
        if (install && notebooks) request.putBoolean("notebooks_requested", true);
        request.apply();
        if (install) TermuxBridge.install(this, notebooks); else TermuxBridge.start(this);
        render(true); checkRuntime();
    }
    private void checkRuntime() {
        if (checking || !prefs.getBoolean("setup_started", false) || editorVisible || loadingPage || connectionError) return;
        checking = true; int checkGeneration = generation;
        TermuxBridge.State before = TermuxBridge.readState(this);
        String token = TermuxBridge.statusToken(this);
        network.execute(() -> {
            JSONObject status = null; boolean ready = false;
            try { status = new JSONObject(fetch(TermuxBridge.STATUS_URL, token, 1800)); } catch (Exception ignored) {}
            boolean current = status != null && before.requestId.equals(status.optString("requestId"));
            // An unrelated HTTP 200 or a booting HTTP 503 must never mean ready.
            if (!before.running || (current && "ready".equals(status.optString("state")))) {
                try {
                    JSONObject health = new JSONObject(fetch(DEFAULT_SERVER_URL + "/healthz", null, 1600));
                    ready = ("alive".equals(health.optString("status")) || "expired".equals(health.optString("status")))
                        && health.has("lastHeartbeat");
                } catch (Exception ignored) {}
            }
            JSONObject result = status; boolean healthy = ready;
            handler.post(() -> {
                checking = false;
                if (isDestroyed() || checkGeneration != generation) return;
                runtimeStatus = result;
                // A setup running under another request id is still the user's setup.
                if (result != null && TermuxBridge.adoptExternalOperation(this, result.optString("requestId"))) {
                    render(true);
                }
                if (result != null) {
                    JSONArray lines = result.optJSONArray("logTail");
                    if (lines != null) { StringBuilder b = new StringBuilder(); for (int i=0; i<lines.length(); i++) b.append(lines.optString(i)).append('\n'); logs = b.toString(); }
                }
                localReady = healthy;
                if (healthy && TermuxBridge.recordReady(this, before.requestId)) {
                    prefs.edit().putBoolean("core_installed", true).apply();
                    // Whether notebooks actually landed is Termux's to answer: the
                    // notebook step can warn and be skipped while setup still
                    // succeeds, and assuming success would hide the way to retry.
                    if (prefs.getBoolean("notebooks_requested", false)) {
                        prefs.edit().putBoolean("notebooks_requested", false).apply();
                        startProbe();
                        return;
                    }
                }
                if (result != null && before.requestId.equals(result.optString("requestId")) && "error".equals(result.optString("state")))
                    TermuxBridge.recordFailure(this, before.requestId, result.optString("message"), logs);
                render(false);
            });
        });
    }
    private static String fetch(String address, String token, int timeout) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(address).openConnection();
        try {
            conn.setConnectTimeout(timeout); conn.setReadTimeout(timeout); conn.setInstanceFollowRedirects(false);
            if (token != null) conn.setRequestProperty("Authorization", "Bearer " + token);
            int code = conn.getResponseCode(); if (code != 200) throw new IOException("HTTP " + code);
            try (InputStream in = conn.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[4096]; int n;
                while ((n = in.read(buf)) != -1) { if (out.size() + n > 65536) throw new IOException("Response too large"); out.write(buf, 0, n); }
                return out.toString("UTF-8");
            }
        } finally { conn.disconnect(); }
    }
    private void buildSteps(int current) {
        steps.removeAllViews();
        String[] labels = {"Install Termux", "Connect your apps", "Set up your workspace", "Open the editor"};
        for (int i=0; i<labels.length; i++) {
            LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(0,dp(9),0,dp(9));
            TextView num = new TextView(this); num.setText(i < current ? "✓" : String.valueOf(i+1));
            num.setTextColor(i <= current ? BLUE : TEXT); num.setTypeface(null,Typeface.BOLD); num.setTextSize(14);
            row.addView(num,new LinearLayout.LayoutParams(dp(32),ViewGroup.LayoutParams.WRAP_CONTENT));
            TextView label = new TextView(this); label.setText(labels[i]); label.setTextSize(14);
            label.setTextColor(i == current ? Color.WHITE : TEXT); label.setTypeface(null,i == current ? Typeface.BOLD : Typeface.NORMAL);
            row.addView(label); steps.addView(row);
        }
    }
    private TextView note(String text) {
        TextView v = new TextView(this); v.setText(text); v.setTextColor(TEXT); v.setTextSize(14); v.setLineSpacing(dp(3),1);
        v.setPadding(0,0,0,dp(10)); detail.addView(v); return v;
    }
    private void action(String text, Runnable run) { primary.setText(text); primary.setOnClickListener(v -> run.run()); }
    private void secondary(String text, Runnable run) { secondary.setVisibility(View.VISIBLE); secondary.setText(text); secondary.setOnClickListener(v -> run.run()); }
    private void smallButton(String label, Runnable run) {
        Button b = new Button(this,null,com.google.android.material.R.attr.borderlessButtonStyle);
        b.setText(label); b.setAllCaps(false); b.setTextColor(BLUE); b.setMinHeight(dp(48));
        b.setOnClickListener(v -> run.run()); detail.addView(b);
    }
    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    private void toast(String text) { Toast.makeText(this,text,Toast.LENGTH_LONG).show(); }
    private void copy(String label, String text) {
        ((ClipboardManager)getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText(label,text));
        toast("Copied. Paste in Termux and press Enter.");
    }
    private void openUrl(String url) {
        try { startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(url))); }
        catch (Exception e) { toast("No app can open this link."); }
    }
    private void openAppSettings(String pkg) { openIntent(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:" + pkg))); }
    private void openIntent(Intent intent) { try { startActivity(intent); } catch (Exception e) { toast("This setting is unavailable on this device."); } }
    private void showHelp() {
        new AlertDialog.Builder(this).setTitle("A little help")
            .setItems(new String[]{"Installation log", "Check the Termux link", "Termux battery settings",
                    "OpenVScode permissions", "How setup works"}, (d,which) -> {
                if (which == 0) showLogs();
                // Reachable at any time: the check also reports what Termux has.
                else if (which == 1) startProbe();
                else if (which == 2) openAppSettings("com.termux");
                else if (which == 3) openAppSettings(getPackageName());
                else new AlertDialog.Builder(this).setTitle("Your workspace, on your phone")
                    .setMessage("Termux runs code-server, Python and C++. OpenVScode displays the editor.\n\nKeep Termux running. If Android stops it, use Start editor to reconnect. Setting Termux battery use to Unrestricted can help.\n\nProjects: ~/OpenVScode_Workspace\nLogs: ~/.local/state/openvscode\n\nTermux’s external-app setting allows apps you grant its Run command permission to run shell commands. Grant this only to apps you trust.")
                    .setPositiveButton("Got it",null).show();
            }).setNegativeButton("Close",null).show();
    }
    private void showLogs() {
        TermuxBridge.State task = TermuxBridge.readState(this);
        String diagnostic = "OpenVScode " + BuildConfig.VERSION_NAME + " · Android " + Build.VERSION.RELEASE
            + "\nTermux installed: " + TermuxBridge.isInstalled(this) + "\nTermux automatable: " + TermuxBridge.canRunCommands(this)
            + "\nCommand permission: " + TermuxBridge.hasRunPermission(this) + "\nLink verified: " + TermuxBridge.isBridgeVerified(this)
            + "\n" + task.message;
        // Both halves matter: Termux's own reply to the last command, and the
        // installer's log. Showing only the log hid what the link check reported.
        if (!task.output.isEmpty()) diagnostic += "\n\n--- Termux reply to the last command ---\n" + task.output;
        if (!logs.isEmpty()) diagnostic += "\n\n--- installation log ---\n" + logs;
        if (logs.isEmpty() && task.output.isEmpty()) diagnostic += "\n\nWaiting for log output. You can also view ~/.local/state/openvscode/install.log in Termux.";
        final String report = diagnostic.replace(TermuxBridge.statusToken(this),"[redacted]");
        TextView text = new TextView(this); text.setText(report); text.setTextIsSelectable(true); text.setTypeface(Typeface.MONOSPACE);
        text.setTextSize(12); text.setPadding(dp(20),dp(12),dp(20),dp(12));
        ScrollView scroll = new ScrollView(this); scroll.addView(text);
        new AlertDialog.Builder(this).setTitle("Setup diagnostics").setView(scroll)
            .setPositiveButton("Close",null).setNeutralButton("Copy log",(d,w) -> {
                ((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("OpenVScode diagnostics",report)); toast("Diagnostics copied.");
            }).show();
    }
    private void showRecovery() {
        new AlertDialog.Builder(this).setTitle("Check Termux first")
            .setMessage("A package download can take several minutes. Open Termux to check for a package or permission prompt.\n\nIf Termux stopped, retry setup. Completed packages and your projects are kept. A lock prevents two installers from changing packages at the same time.")
            .setPositiveButton("Open Termux",(d,w) -> TermuxBridge.openTermux(this))
            .setNeutralButton("Retry setup",(d,w) -> { TermuxBridge.clearPending(this); runRuntime(!prefs.getBoolean("core_installed",false)); })
            .setNegativeButton("Keep waiting",null).show();
    }
    private void showRemoteDialog() {
        EditText input = new EditText(this); input.setSingleLine(true); input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        input.setHint("https://your-server.example"); input.setText(prefs.getString("server_url",""));
        LinearLayout box = new LinearLayout(this); box.setPadding(dp(24),dp(8),dp(24),0); box.addView(input,new LinearLayout.LayoutParams(-1,-2));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Connect to a server")
            .setMessage("Enter your code-server or VS Code server address. Use HTTPS for a server outside this phone.")
            .setView(box).setNegativeButton("Cancel",null).setPositiveButton("Connect",null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            List<String> choices = candidateUrls(input.getText().toString());
            if (choices.isEmpty()) { input.setError("Enter a valid HTTP or HTTPS address."); return; }
            String url = choices.get(0); prefs.edit().putString("server_url",url).putString("mode","remote").apply();
            dialog.dismiss(); connect(url);
        })); dialog.show();
    }

    @SuppressLint("SetJavaScriptEnabled") private void createWebView() {
        FrameLayout container = findViewById(R.id.webContainer);
        webView = new WebView(this); container.addView(webView,new FrameLayout.LayoutParams(-1,-1));
        webView.setBackgroundColor(Color.rgb(30,30,30));
        WebSettings s = webView.getSettings(); s.setJavaScriptEnabled(true); s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false); s.setAllowContentAccess(true); s.setUseWideViewPort(true); s.setLoadWithOverviewMode(true);
        s.setSupportZoom(false); s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> cb, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null); fileCallback = cb;
                try { files.launch(params.createIntent()); } catch (Exception e) { fileCallback.onReceiveValue(null); fileCallback=null; toast("No file picker is available."); }
                return true;
            }
        });
        webView.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String scheme = request.getUrl().getScheme();
                if ("https".equals(scheme) || "http".equals(scheme)) {
                    if (request.isForMainFrame() && request.hasGesture() && !isMatchingServer(request.getUrl().toString(),serverUrl)) {
                        openUrl(request.getUrl().toString()); return true;
                    }
                    return false;
                }
                if (request.hasGesture() && ("mailto".equals(scheme) || "tel".equals(scheme))) openUrl(request.getUrl().toString());
                return true;
            }
            @Override public void onPageFinished(WebView view, String url) {
                if (pageFailed || !loadingPage || "about:blank".equals(url)) return;
                loadingPage = false; editorVisible = true; setup.setVisibility(View.GONE); editor.setVisibility(View.VISIBLE);
                ((TextView)findViewById(R.id.editorLabel)).setText(isMatchingServer(serverUrl,DEFAULT_SERVER_URL) ? "OpenVScode · Local" : "OpenVScode · Connected");
                if (foreground) {
                    if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(MainActivity.this,Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
                        && !prefs.getBoolean("notifications_asked",false)) {
                        prefs.edit().putBoolean("notifications_asked",true).apply(); notifications.launch(Manifest.permission.POST_NOTIFICATIONS);
                    }
                    try { ContextCompat.startForegroundService(MainActivity.this,new Intent(MainActivity.this,VScodeService.class)); }
                    catch (RuntimeException ignored) { /* Editor remains usable if Android declines session support. */ }
                }
            }
            @Override public void onReceivedError(WebView view, WebResourceRequest req, WebResourceError error) {
                if (req.isForMainFrame()) connectionFailed("The editor is unreachable. Start it again in Termux, or check your server address.");
            }
            @Override public void onReceivedHttpError(WebView view, WebResourceRequest req, WebResourceResponse response) {
                if (req.isForMainFrame() && response.getStatusCode() >= 400 && response.getStatusCode() != 401)
                    connectionFailed("The server returned HTTP " + response.getStatusCode() + ". Check the server, then reconnect.");
            }
            @Override public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                container.removeView(view); view.destroy(); webView = null; createWebView();
                connectionFailed("Android closed the editor view to free memory. Reopen it to continue."); return true;
            }
        });
        webView.setDownloadListener((url,agent,disposition,type,length) -> {
            if (url.startsWith("http://") || url.startsWith("https://")) openUrl(url);
            else toast("Use the editor terminal to save this file to your workspace.");
        });
    }
    private void connect(String url) {
        generation++; serverUrl = url; pageFailed = false; connectionError = false; loadingPage = true; editorVisible = false;
        editor.setVisibility(View.VISIBLE); setup.setVisibility(View.VISIBLE);
        title.setText("Opening your editor."); subtitle.setText("Connecting to your workspace…");
        steps.removeAllViews(); detail.removeAllViews(); progress.setVisibility(View.VISIBLE); progress.setIndeterminate(true);
        caption.setVisibility(View.GONE); secondary.setVisibility(View.GONE);
        action("Back to setup", () -> { webView.stopLoading(); loadingPage=false; editorVisible=false; render(true); });
        webView.loadUrl(url);
        int loadGeneration = generation;
        handler.postDelayed(() -> { if (loadingPage && generation == loadGeneration) connectionFailed("The editor is taking too long to open. Check that Termux or your server is still running."); },30000);
    }
    private void connectionFailed(String message) {
        pageFailed = true; loadingPage = false; editorVisible = false; connectionError = true;
        setup.setVisibility(View.VISIBLE); editor.setVisibility(View.GONE); screen="connection_error";
        title.setText("Connection interrupted."); subtitle.setText(message); detail.removeAllViews(); steps.removeAllViews();
        progress.setVisibility(View.GONE); caption.setVisibility(View.GONE);
        action("Reconnect", () -> connect(serverUrl));
        secondary("Back to setup", () -> { prefs.edit().putString("mode","local").apply(); localReady=false; notice=""; connectionError=false; render(true); });
    }
    private void showSession() {
        new AlertDialog.Builder(this).setTitle("Your session")
            .setItems(new String[]{"Reload editor", "Show / hide coding keys", "Open Termux", "Workspace setup", "Release background wake lock"},(d,which) -> {
                if (which==0) connect(serverUrl);
                else if (which==1) { View bar=findViewById(R.id.keyboardToolbar); bar.setVisibility(bar.getVisibility()==View.VISIBLE ? View.GONE : View.VISIBLE); }
                else if (which==2) TermuxBridge.openTermux(this);
                else if (which==3) { editorVisible=false; prefs.edit().putString("mode","local").apply(); render(true); }
                else { stopService(new Intent(this,VScodeService.class)); toast("Wake lock released. Termux manages the running server."); }
            }).setNegativeButton("Close",null).show();
    }
    private void buildKeybar() {
        String[] labels={"Esc","Tab","Ctrl","Alt","{","}","(",")","[","]","/",";","←","↓","↑","→"};
        for (String key:labels) {
            Button b=new Button(this); b.setText(key); b.setAllCaps(false); b.setTextSize(12); b.setTextColor(TEXT);
            b.setMinWidth(dp(48)); b.setMinimumWidth(dp(48)); b.setPadding(dp(8),0,dp(8),0); b.setFocusable(false);
            keys.addView(b,new LinearLayout.LayoutParams(-2,dp(48)));
            b.setOnClickListener(v -> {
                if (key.equals("Ctrl")) { ctrl=!ctrl; b.setTextColor(ctrl ? BLUE:TEXT); return; }
                if (key.equals("Alt")) { alt=!alt; b.setTextColor(alt ? BLUE:TEXT); return; }
                sendKey(key);
                ctrl=false; alt=false; for(int i=0;i<keys.getChildCount();i++) ((Button)keys.getChildAt(i)).setTextColor(TEXT);
            });
        }
    }
    private void sendKey(String key) {
        webView.requestFocus();
        int code=0;
        switch(key) { case "Esc": code=KeyEvent.KEYCODE_ESCAPE; break; case "Tab": code=KeyEvent.KEYCODE_TAB; break;
            case "←": code=KeyEvent.KEYCODE_DPAD_LEFT; break; case "→": code=KeyEvent.KEYCODE_DPAD_RIGHT; break;
            case "↑": code=KeyEvent.KEYCODE_DPAD_UP; break; case "↓": code=KeyEvent.KEYCODE_DPAD_DOWN; break; }
        if(code!=0) {
            int meta=(ctrl?KeyEvent.META_CTRL_ON:0)|(alt?KeyEvent.META_ALT_ON:0);
            long time=android.os.SystemClock.uptimeMillis();
            webView.dispatchKeyEvent(new KeyEvent(time,time,KeyEvent.ACTION_DOWN,code,0,meta));
            webView.dispatchKeyEvent(new KeyEvent(time,time,KeyEvent.ACTION_UP,code,0,meta));
        } else {
            // Use WebView's IME connection so Monaco receives real text input.
            android.view.inputmethod.InputConnection input=webView.onCreateInputConnection(new android.view.inputmethod.EditorInfo());
            if(input!=null) input.commitText(key,1);
        }
    }
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if(editorVisible && (ctrl || alt) && event.getKeyCode()!=KeyEvent.KEYCODE_BACK) {
            KeyEvent modified=new KeyEvent(event.getDownTime(),event.getEventTime(),event.getAction(),event.getKeyCode(),event.getRepeatCount(),
                event.getMetaState()|(ctrl?KeyEvent.META_CTRL_ON:0)|(alt?KeyEvent.META_ALT_ON:0));
            boolean handled=webView.dispatchKeyEvent(modified);
            if(event.getAction()==KeyEvent.ACTION_UP) { ctrl=false;alt=false;for(int i=0;i<keys.getChildCount();i++) ((Button)keys.getChildAt(i)).setTextColor(TEXT); }
            return handled;
        }
        return super.dispatchKeyEvent(event);
    }
    @Override public void onBackPressed() {
        if (loadingPage) { webView.stopLoading(); loadingPage=false; render(true); }
        else if(editorVisible) showSession(); else moveTaskToBack(true);
    }
    static List<String> candidateUrls(String raw) { return ServerAddress.candidates(raw); }
    static String hostOf(String raw) { return ServerAddress.hostOf(raw); }
    static boolean isLocalAddress(String host) { return ServerAddress.isLocal(host); }
    static boolean isMatchingServer(String page, String server) { return ServerAddress.sameOrigin(page,server); }
    static String subnetPrefixOf(String ip) { if(ip==null)return null;int dot=ip.lastIndexOf('.');return dot>0?ip.substring(0,dot+1):null; }
}
