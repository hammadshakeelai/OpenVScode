package com.openvscode.mobile;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import android.view.ViewGroup;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private static final String PREFS_NAME = "openvscode_prefs";
    private static final String KEY_SERVER_URL = "server_url";
    private static final String KEY_RECENTS = "recent_servers";
    private static final int MAX_RECENTS = 6;
    /** Ports worth probing when scanning the local network for an IDE. */
    private static final int[] SCAN_PORTS = {8080, 8100, 3000, 8000, 8443, 9000, 4444};
    /** probeServer() verdicts. */
    private static final int PROBE_NONE = 0;
    private static final int PROBE_HTTP = 1;
    private static final int PROBE_IDE = 2;
    public static final String DEFAULT_SERVER_URL = "http://127.0.0.1:8080";

    private String currentServerUrl;
    private SharedPreferences prefs;

    private WebView webView;
    private View loadingOverlay;
    private ProgressBar progressBar;
    private TextView statusTitle;
    private TextView statusSubtitle;
    private LinearLayout serverConfigContainer;
    private EditText editServerUrl;
    private Button btnConnect;
    private Button btnRetry;
    private View keyboardToolbar;
    private LinearLayout keysContainer;

    private boolean isCtrlActive = false;
    private boolean isAltActive = false;
    private Button ctrlButton;
    private Button altButton;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private boolean isServerReady = false;
    private boolean isServiceStarted = false;
    private boolean awaitingFirstLoad = false;
    private boolean autoDiscoveryDone = false;
    private boolean autoConnectUsed = false;
    private String pendingError = null;
    private boolean loadErrored = false;
    private LinearLayout recentsRow;
    private Button btnScan;
    private Button btnSetup;
    private Button btnInstallRootfs;

    // Runtime permission request for Android 13+ (POST_NOTIFICATIONS)
    private final ActivityResultLauncher<String> requestPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                Log.i(TAG, "Notification permission result: " + isGranted);
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        // Diagnostic, off by default. Run it with:
        //   adb shell am start -n <pkg>/.MainActivity --ez run_probe true
        if (getIntent() != null && getIntent().getBooleanExtra("run_probe", false)) {
            BootstrapProbe.run(this);
        }
        currentServerUrl = prefs.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL);

        Intent startIntent = getIntent();
        if (startIntent != null && startIntent.hasExtra("server_url")) {
            String extraUrl = startIntent.getStringExtra("server_url");
            if (extraUrl != null && !extraUrl.trim().isEmpty()) {
                currentServerUrl = extraUrl.trim();
                prefs.edit().putString(KEY_SERVER_URL, currentServerUrl).apply();
            }
        }

        webView = findViewById(R.id.webView);
        loadingOverlay = findViewById(R.id.loadingOverlay);
        progressBar = findViewById(R.id.progressBar);
        statusTitle = findViewById(R.id.statusTitle);
        statusSubtitle = findViewById(R.id.statusSubtitle);
        serverConfigContainer = findViewById(R.id.serverConfigContainer);
        editServerUrl = findViewById(R.id.editServerUrl);
        btnConnect = findViewById(R.id.btnConnect);
        btnRetry = findViewById(R.id.btnRetry);
        keyboardToolbar = findViewById(R.id.keyboardToolbar);
        keysContainer = findViewById(R.id.keysContainer);

        // Adjust keyboardToolbar margin when software keyboard (IME) appears or disappears
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content), (v, windowInsets) -> {
            int imeHeight = windowInsets.getInsets(WindowInsetsCompat.Type.ime()).bottom;
            int navBarHeight = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom;
            int bottomInset = Math.max(imeHeight, navBarHeight);

            if (keyboardToolbar != null) {
                ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) keyboardToolbar.getLayoutParams();
                if (lp != null && lp.bottomMargin != bottomInset) {
                    lp.bottomMargin = bottomInset;
                    keyboardToolbar.setLayoutParams(lp);
                }
            }
            return windowInsets;
        });

        editServerUrl.setText(currentServerUrl);
        buildServerTools();

        // Setup WebView settings
        initWebView();

        // Populate bottom coding touchbar
        setupKeybar();

        // Connect button listener (saves URL and retries)
        btnConnect.setOnClickListener(v -> {
            String inputUrl = editServerUrl.getText().toString().trim();
            if (!inputUrl.isEmpty()) {
                // Stored raw on purpose. candidateUrls() settles the scheme and strips
                // noise at connect time, so "192.168.1.5:8100", "my-box.local:3000" and
                // "https://abc.ngrok.io/?tkn=x" are all valid things to type here.
                currentServerUrl = inputUrl;
                prefs.edit().putString(KEY_SERVER_URL, currentServerUrl).apply();
                startPollingCycle();
            }
        });

        // Retry button listener
        btnRetry.setOnClickListener(v -> startPollingCycle());

        // Begin initial polling
        startPollingCycle();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null && intent.hasExtra("server_url")) {
            String extraUrl = intent.getStringExtra("server_url");
            if (extraUrl != null && !extraUrl.trim().isEmpty()) {
                currentServerUrl = extraUrl.trim();
                prefs.edit().putString(KEY_SERVER_URL, currentServerUrl).apply();
                if (editServerUrl != null) {
                    editServerUrl.setText(currentServerUrl);
                }
                startPollingCycle();
            }
        }
    }

    private void requestNotificationPermission() {
        // Kept, but it cannot succeed while we target 28. Measured on API 36:
        // a legacy-target app calling this is auto-denied with no dialog, and
        // the app sits at importance=NONE, so the foreground-service
        // notification never appears. Enabling it requires Settings -> Apps ->
        // OpenVScode -> Notifications. Guarding the call on targetSdk was tried
        // and is worse: it stops the app asking on devices where it would work.
        // Consequence: the notification is not a reliable way to stop the
        // session, so onDestroy() stops the service and the wake lock carries
        // its own 30-minute timeout.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
            }
        }
    }

    private void startPollingCycle() {
        serverConfigContainer.setVisibility(View.GONE);
        progressBar.setVisibility(View.VISIBLE);
        statusTitle.setText(R.string.server_starting);
        statusSubtitle.setText(getString(R.string.server_loading_sub, currentServerUrl));
        isServerReady = false;
        autoDiscoveryDone = false;
        ensureLocalIdeRunning();
        pollServerReadiness();
    }

    /**
     * If the rootfs is installed, make sure the IDE inside it is running. The
     * existing poll against 127.0.0.1:8080 then picks it up like any other
     * server, so nothing else in the connect path needs to know about this.
     */
    private void ensureLocalIdeRunning() {
        if (!RootfsInstaller.isInstalled(this) || RootfsLauncher.isRunning()) {
            return;
        }
        String problem = RootfsLauncher.pathProblem(this);
        if (problem != null) {
            Log.e(TAG, "refusing to launch: " + problem);
            return;
        }
        // Its own thread: the shared executor is busy with the poll loop.
        new Thread(() -> {
            try {
                RootfsLauncher.start(this);
            } catch (Exception e) {
                Log.e(TAG, "could not start the local IDE", e);
            }
        }, "ide-launch").start();
    }

    private void startBackgroundService() {
        if (isServiceStarted) return;
        // Ask for notification access here rather than at cold start: the prompt
        // is only meaningful once there is actually a session to show, and at
        // launch it lands on top of the connect screen before anything happens.
        requestNotificationPermission();
        Intent serviceIntent = new Intent(this, VScodeService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(this, serviceIntent);
        } else {
            startService(serviceIntent);
        }
        isServiceStarted = true;
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void initWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);

        // Force dark background
        webView.setBackgroundColor(Color.parseColor("#181818"));

        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return false; // Load all URLs inside the app
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                Log.i(TAG, "WebView onPageStarted: " + url);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                Log.i(TAG, "WebView onPageFinished: " + url + " | currentServerUrl=" + currentServerUrl);
                boolean matched = url != null && isMatchingServer(url, currentServerUrl);
                // A server may redirect anywhere it likes: an https upgrade, an SSO hop,
                // a tunnel hostname. Demanding host+port equality strands the user on the
                // overlay with a fully loaded IDE behind it, so also accept the first
                // clean load of a navigation we started ourselves.
                boolean firstLoadOk = awaitingFirstLoad && !loadErrored
                        && url != null && !url.startsWith("about:");
                if (matched || firstLoadOk) {
                    awaitingFirstLoad = false;
                    Log.i(TAG, "Dismissing overlay (matched=" + matched + " firstLoadOk=" + firstLoadOk + ")");
                    loadingOverlay.animate().alpha(0f).setDuration(300).withEndAction(() -> {
                        loadingOverlay.setVisibility(View.GONE);
                    });
                    // Start background wake lock service only after server actually loads
                    startBackgroundService();
                } else {
                    Log.w(TAG, "onPageFinished did not dismiss overlay: url=" + url + " server=" + currentServerUrl);
                }
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, android.webkit.WebResourceError error) {
                super.onReceivedError(view, request, error);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && request != null && request.isForMainFrame()) {
                    loadErrored = true;
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    Log.e(TAG, "WebView onReceivedError: " + error.getDescription() + " code=" + error.getErrorCode() + " url=" + request.getUrl());
                }
            }
        });
    }

    static boolean isMatchingServer(String pageUrl, String serverUrl) {
        if (pageUrl == null || serverUrl == null) return false;
        if (pageUrl.startsWith(serverUrl)) return true;
        try {
            URI pageUri = new URI(pageUrl);
            URI serverUri = new URI(serverUrl);
            String pageHost = pageUri.getHost();
            String serverHost = serverUri.getHost();
            int pagePort = pageUri.getPort() == -1 ? ("https".equalsIgnoreCase(pageUri.getScheme()) ? 443 : 80) : pageUri.getPort();
            int serverPort = serverUri.getPort() == -1 ? ("https".equalsIgnoreCase(serverUri.getScheme()) ? 443 : 80) : serverUri.getPort();

            if (("127.0.0.1".equals(serverHost) || "localhost".equalsIgnoreCase(serverHost)) &&
                    ("127.0.0.1".equals(pageHost) || "localhost".equalsIgnoreCase(pageHost))) {
                return pagePort == serverPort;
            }
            return pageHost != null && pageHost.equalsIgnoreCase(serverHost) && pagePort == serverPort;
        } catch (Exception e) {
            return pageUrl.startsWith(serverUrl);
        }
    }

    private void pollServerReadiness() {
        executor.execute(() -> {
            int attempts = 0;
            String lastError = "Connection refused";
            while (!isServerReady && attempts < 8) {
                attempts++;
                final int currentAttempt = attempts;
                mainHandler.post(() -> {
                    if (!isServerReady) {
                        statusSubtitle.setText("Attempt " + currentAttempt + "/8 — Connecting to " + currentServerUrl);
                    }
                });

                for (String candidate : candidateUrls(currentServerUrl)) {
                    try {
                        HttpURLConnection conn = (HttpURLConnection) new URL(candidate).openConnection();
                        conn.setConnectTimeout(4000);
                        conn.setReadTimeout(4000);
                        conn.setInstanceFollowRedirects(true);
                        conn.setRequestMethod("GET");
                        final int code = conn.getResponseCode();
                        conn.disconnect();

                        // ANY http status means something is listening and speaking HTTP.
                        // A whitelist of "good" codes strands the user on real servers:
                        // `code serve-web` answers 202 while it unpacks itself, proxies
                        // answer 204/418, a booting IDE answers 502/503.
                        if (code > 0) {
                            isServerReady = true;
                            final String winner = candidate;
                            mainHandler.post(() -> {
                                currentServerUrl = winner;
                                prefs.edit().putString(KEY_SERVER_URL, winner).apply();
                                rememberServer(winner);
                                editServerUrl.setText(winner);
                                Log.i(TAG, "Server reached (HTTP " + code + "). Loading: " + winner);
                                awaitingFirstLoad = true;
                                loadErrored = false;
                                webView.loadUrl(winner);
                            });
                            return;
                        }
                    } catch (Exception e) {
                        lastError = e.getClass().getSimpleName() + ": "
                                + (e.getMessage() != null ? e.getMessage() : "unreachable");
                        Log.w(TAG, "Attempt " + currentAttempt + " -> " + candidate + " failed: " + lastError);
                    }
                }

                try {
                    Thread.sleep(900);
                } catch (InterruptedException e) {
                    break;
                }
            }

            if (!isServerReady) {
                final String finalError = lastError;
                // Do not dead-end on a form. Sweep the network first and, on the
                // first failure of a launch, connect to what we find.
                mainHandler.post(() -> beginAutoDiscovery(finalError));
            }
        });
    }

    // ---- Address handling ------------------------------------------------
    //
    // The app should reach whatever the user can reach: a server on the phone
    // itself, a laptop on the same Wi-Fi, a box addressed by hostname, or an
    // https tunnel on the public internet. Nothing below assumes an address.

    /**
     * Expands loose input into an ordered list of URLs to try.
     * Accepts "192.168.1.5:8100", "localhost:8080", "my-box.local",
     * "https://abc.ngrok.io", "10.0.0.4:8443/?tkn=secret".
     * An explicit scheme is honoured as typed; without one, local addresses are
     * tried over http first and public ones over https first.
     */
    static List<String> candidateUrls(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) return out;
        String s = raw.trim().replaceAll("\\s+", "");
        if (s.isEmpty()) return out;

        String scheme = null;
        int sep = s.indexOf("://");
        if (sep > 0) {
            scheme = s.substring(0, sep).toLowerCase(Locale.ROOT);
            s = s.substring(sep + 3);
        }
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        if (s.isEmpty()) return out;

        if (scheme != null) {
            out.add(scheme + "://" + s);
            return out;
        }
        if (isLocalAddress(hostOf(s))) {
            out.add("http://" + s);
            out.add("https://" + s);
        } else {
            out.add("https://" + s);
            out.add("http://" + s);
        }
        return out;
    }

    /** Host portion of an authority such as "1.2.3.4:8100/path?q=1". */
    static String hostOf(String authority) {
        if (authority == null) return "";
        String h = authority;
        int cut = h.indexOf('/');
        if (cut >= 0) h = h.substring(0, cut);
        cut = h.indexOf('?');
        if (cut >= 0) h = h.substring(0, cut);
        int at = h.lastIndexOf('@');
        if (at >= 0) h = h.substring(at + 1);
        if (h.startsWith("[")) {                     // IPv6 literal
            int end = h.indexOf(']');
            return end > 0 ? h.substring(0, end + 1) : h;
        }
        cut = h.indexOf(':');
        if (cut >= 0) h = h.substring(0, cut);
        return h;
    }

    /** Loopback, RFC1918, link-local, mDNS name, or a bare hostname. */
    static boolean isLocalAddress(String host) {
        if (host == null || host.isEmpty()) return false;
        String h = host.toLowerCase(Locale.ROOT);
        if (h.equals("localhost") || h.equals("::1") || h.equals("[::1]")) return true;
        if (h.endsWith(".local") || h.endsWith(".lan") || h.endsWith(".home")) return true;
        if (h.startsWith("127.") || h.startsWith("10.") || h.startsWith("192.168.")) return true;
        if (h.startsWith("169.254.")) return true;
        if (h.startsWith("172.")) {
            String[] parts = h.split("\\.");
            if (parts.length > 1) {
                try {
                    int second = Integer.parseInt(parts[1]);
                    if (second >= 16 && second <= 31) return true;
                } catch (NumberFormatException ignored) { }
            }
        }
        return !h.contains(".");                     // bare LAN hostname
    }

    // ---- Recent servers --------------------------------------------------

    private void rememberServer(String url) {
        Set<String> seen = new LinkedHashSet<>();
        seen.add(url);
        seen.addAll(recentServers());
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (String s : seen) {
            if (n++ >= MAX_RECENTS) break;
            if (sb.length() > 0) sb.append('\n');
            sb.append(s);
        }
        prefs.edit().putString(KEY_RECENTS, sb.toString()).apply();
        mainHandler.post(this::renderRecents);
    }

    private List<String> recentServers() {
        List<String> out = new ArrayList<>();
        for (String s : prefs.getString(KEY_RECENTS, "").split("\n")) {
            if (!s.trim().isEmpty()) out.add(s.trim());
        }
        return out;
    }

    /** Adds the scan button and the recents strip under the address box. */
    private void buildServerTools() {
        btnScan = new Button(this);
        btnScan.setText(R.string.scan_wifi);
        btnScan.setAllCaps(false);
        btnScan.setTextSize(13f);
        btnScan.setBackgroundColor(Color.parseColor("#333333"));
        btnScan.setTextColor(Color.parseColor("#CCCCCC"));
        btnScan.setPadding(dpToPx(12), dpToPx(8), dpToPx(12), dpToPx(8));
        LinearLayout.LayoutParams scanParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        scanParams.topMargin = dpToPx(10);
        btnScan.setLayoutParams(scanParams);
        btnScan.setOnClickListener(v -> scanLanManually());
        serverConfigContainer.addView(btnScan);

        buildInstallButton();
        buildSetupButton();

        recentsRow = new LinearLayout(this);
        recentsRow.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowParams.topMargin = dpToPx(8);
        recentsRow.setLayoutParams(rowParams);
        serverConfigContainer.addView(recentsRow);

        renderRecents();
    }

    private void renderRecents() {
        if (recentsRow == null) return;
        recentsRow.removeAllViews();
        List<String> recents = recentServers();
        if (recents.isEmpty()) return;
        addRowLabel(getString(R.string.recent_label));
        for (String url : recents) addServerChoice(url);
    }

    private void addRowLabel(String text) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextSize(12f);
        label.setTextColor(Color.parseColor("#888888"));
        label.setPadding(0, dpToPx(6), 0, dpToPx(2));
        recentsRow.addView(label);
    }

    /** One tappable address under the input box. */
    private void addServerChoice(final String url) {
        Button b = new Button(this);
        b.setText(url);
        b.setAllCaps(false);
        b.setTextSize(12f);
        b.setTextColor(Color.parseColor("#CCCCCC"));
        b.setBackgroundColor(Color.parseColor("#2a2a2a"));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dpToPx(4);
        b.setLayoutParams(lp);
        b.setOnClickListener(v -> {
            editServerUrl.setText(url);
            currentServerUrl = url;
            prefs.edit().putString(KEY_SERVER_URL, url).apply();
            startPollingCycle();
        });
        recentsRow.addView(b);
    }


    // ---- Self-contained install (no Termux) ------------------------------

    /**
     * Downloads and unpacks the Linux rootfs that carries Python, C++ and
     * Jupyter. Unlike the Termux path this needs no second app — at the cost of
     * a large one-time download, which is why the button states the size.
     */
    private void buildInstallButton() {
        btnInstallRootfs = new Button(this);
        btnInstallRootfs.setAllCaps(false);
        btnInstallRootfs.setTextSize(14f);
        btnInstallRootfs.setTextColor(Color.parseColor("#FFFFFF"));
        btnInstallRootfs.setBackgroundColor(Color.parseColor("#005a9e"));
        btnInstallRootfs.setPadding(dpToPx(12), dpToPx(10), dpToPx(12), dpToPx(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dpToPx(10);
        btnInstallRootfs.setLayoutParams(lp);
        refreshInstallButton();
        btnInstallRootfs.setOnClickListener(v -> startRootfsInstall());
        serverConfigContainer.addView(btnInstallRootfs, 0);
    }

    private void refreshInstallButton() {
        if (btnInstallRootfs == null) return;
        if (RootfsInstaller.archSuffix() == null) {
            btnInstallRootfs.setText(R.string.install_unsupported_cpu);
            btnInstallRootfs.setEnabled(false);
        } else if (RootfsInstaller.isInstalled(this)) {
            btnInstallRootfs.setText(R.string.install_reinstall);
        } else {
            btnInstallRootfs.setText(R.string.install_rootfs);
        }
    }

    private void startRootfsInstall() {
        btnInstallRootfs.setEnabled(false);
        serverConfigContainer.setVisibility(View.GONE);
        progressBar.setVisibility(View.VISIBLE);
        statusTitle.setText(R.string.install_running_title);
        statusSubtitle.setText(R.string.install_running_desc);

        // A test build can point the installer at a local server:
        //   adb shell am start -n <pkg>/.MainActivity --es rootfs_url http://10.0.2.2:8200
        String override = getIntent() != null ? getIntent().getStringExtra("rootfs_url") : null;
        RootfsInstaller.Progress progress = new RootfsInstaller.Progress() {
            @Override
            public void onStage(final String stage) {
                mainHandler.post(() -> statusTitle.setText(stage));
            }

            @Override
            public void onProgress(final long done, final long total) {
                mainHandler.post(() -> {
                    if (total > 0) {
                        int pct = (int) (done * 100 / total);
                        statusSubtitle.setText(getString(R.string.install_progress,
                                pct, RootfsInstaller.human(done), RootfsInstaller.human(total)));
                    } else {
                        statusSubtitle.setText(RootfsInstaller.human(done));
                    }
                });
            }

            @Override
            public void onComplete(final java.io.File rootfs) {
                mainHandler.post(() -> {
                    btnInstallRootfs.setEnabled(true);
                    refreshInstallButton();
                    Log.i(TAG, "rootfs ready at " + rootfs);
                    // Straight into starting it — the point of installing.
                    statusTitle.setText(R.string.install_done_title);
                    statusSubtitle.setText(R.string.install_done_desc);
                    currentServerUrl = DEFAULT_SERVER_URL;
                    prefs.edit().putString(KEY_SERVER_URL, currentServerUrl).apply();
                    startPollingCycle();
                });
            }

            @Override
            public void onError(final String message) {
                mainHandler.post(() -> {
                    progressBar.setVisibility(View.GONE);
                    statusTitle.setText(R.string.install_failed_title);
                    statusSubtitle.setText(message);
                    serverConfigContainer.setVisibility(View.VISIBLE);
                    btnInstallRootfs.setEnabled(true);
                    refreshInstallButton();
                });
            }
        };

        if (override != null && !override.trim().isEmpty()) {
            Log.i(TAG, "installing from override URL " + override);
            RootfsInstaller.install(this, override.trim(), progress);
        } else {
            RootfsInstaller.install(this, progress);
        }
    }

    // ---- One-tap provisioning through Termux -----------------------------
    //
    // Termux exposes a RUN_COMMAND service that other apps may drive, so the
    // whole Python / C++ / Jupyter install can be kicked off from a button here
    // instead of the user typing commands. Two things gate it, both one-time:
    // this app holds com.termux.permission.RUN_COMMAND, and Termux itself needs
    // allow-external-apps=true in ~/.termux/termux.properties.

    private static final String TERMUX_PKG = "com.termux";
    private static final String TERMUX_SERVICE = "com.termux.app.RunCommandService";
    private static final String TERMUX_BASH = "/data/data/com.termux/files/usr/bin/bash";
    private static final String INSTALL_URL =
            "https://raw.githubusercontent.com/hammadshakeelai/OpenVScode/master/install.sh";

    /**
     * The whole provision, as one shell line — the same one the README tells
     * people to paste, so the button and the documented command cannot drift
     * apart. install.sh is idempotent, so tapping this twice is harmless.
     */
    private static String bootstrapCommand() {
        return "echo '=== OpenVScode Mobile: setting up your IDE ==='; "
                + "pkg install -y curl >/dev/null 2>&1; "
                + "curl -fsSL " + INSTALL_URL + " | bash";
    }

    private boolean isTermuxInstalled() {
        try {
            getPackageManager().getPackageInfo(TERMUX_PKG, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /**
     * Fires the install in a visible Termux session. Deliberately foreground:
     * the toolchain build takes minutes and the user should see it moving
     * rather than stare at a spinner wondering whether anything is happening.
     */
    private void launchTermuxSetup() {
        if (!isTermuxInstalled()) {
            promptInstallTermux();
            return;
        }
        try {
            Intent intent = new Intent();
            intent.setClassName(TERMUX_PKG, TERMUX_SERVICE);
            intent.setAction("com.termux.RUN_COMMAND");
            intent.putExtra("com.termux.RUN_COMMAND_PATH", TERMUX_BASH);
            intent.putExtra("com.termux.RUN_COMMAND_ARGUMENTS",
                    new String[]{"-c", bootstrapCommand()});
            intent.putExtra("com.termux.RUN_COMMAND_WORKDIR",
                    "/data/data/com.termux/files/home");
            intent.putExtra("com.termux.RUN_COMMAND_BACKGROUND", false);
            intent.putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0");
            startService(intent);

            statusTitle.setText(R.string.setup_running_title);
            statusSubtitle.setText(R.string.setup_running_desc);
            Log.i(TAG, "Dispatched setup to Termux");
        } catch (Exception e) {
            // Almost always allow-external-apps being unset, which surfaces as a
            // SecurityException. Say so plainly instead of a generic failure.
            Log.e(TAG, "Termux RUN_COMMAND rejected", e);
            statusTitle.setText(R.string.setup_blocked_title);
            statusSubtitle.setText(R.string.setup_blocked_desc);
        }
    }

    private void promptInstallTermux() {
        statusTitle.setText(R.string.termux_missing_title);
        statusSubtitle.setText(R.string.termux_missing_desc);
        try {
            startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://f-droid.org/en/packages/com.termux/")));
        } catch (Exception e) {
            Log.w(TAG, "Could not open the F-Droid page", e);
        }
    }

    /** The provisioning button, shown above the manual address controls. */
    private void buildSetupButton() {
        btnSetup = new Button(this);
        btnSetup.setText(isTermuxInstalled()
                ? R.string.setup_auto : R.string.setup_install_termux);
        btnSetup.setAllCaps(false);
        btnSetup.setTextSize(14f);
        btnSetup.setTextColor(Color.parseColor("#FFFFFF"));
        btnSetup.setBackgroundColor(Color.parseColor("#0e8a3e"));
        btnSetup.setPadding(dpToPx(12), dpToPx(10), dpToPx(12), dpToPx(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dpToPx(10);
        btnSetup.setLayoutParams(lp);
        btnSetup.setOnClickListener(v -> launchTermuxSetup());
        serverConfigContainer.addView(btnSetup, 0);
    }

    // ---- Local network discovery ----------------------------------------

    /** "192.168.18." for a device at 192.168.18.56, or null when off-LAN. */
    static String subnetPrefixOf(String ipv4) {
        if (ipv4 == null) return null;
        int dot = ipv4.lastIndexOf('.');
        return dot > 0 ? ipv4.substring(0, dot + 1) : null;
    }

    private String localIpv4() {
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            while (ifaces != null && ifaces.hasMoreElements()) {
                NetworkInterface nif = ifaces.nextElement();
                if (!nif.isUp() || nif.isLoopback()) continue;
                Enumeration<InetAddress> addrs = nif.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (addr instanceof Inet4Address && addr.isSiteLocalAddress()) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not determine local IPv4", e);
        }
        return null;
    }

    /**
     * Classifies what is answering at a URL.
     *
     * An open TCP port is not enough to connect to blindly — a home network is
     * full of routers on 8080 and printers on 9000, and this phone's own subnet
     * has an Apache install sitting on 8080 right next to the IDE. So fetch the
     * root and look for something that identifies a code editor before treating
     * a hit as somewhere worth sending the user.
     */
    private static int probeServer(String url, int timeoutMs) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestMethod("GET");
            int code = conn.getResponseCode();
            if (code <= 0) return PROBE_NONE;

            StringBuilder body = new StringBuilder();
            InputStream in = null;
            try {
                in = (code >= 400) ? conn.getErrorStream() : conn.getInputStream();
                if (in != null) {
                    byte[] buf = new byte[8192];
                    int n, total = 0;
                    while (total < 65536 && (n = in.read(buf)) > 0) {
                        body.append(new String(buf, 0, n, "UTF-8"));
                        total += n;
                    }
                }
            } catch (Exception ignored) {
                // headers were enough to prove HTTP; body is a bonus
            } finally {
                if (in != null) try { in.close(); } catch (Exception ignored) { }
            }

            String text = body.toString().toLowerCase(Locale.ROOT);
            if (text.contains("vscode") || text.contains("code-server")
                    || text.contains("workbench") || text.contains("openvscode")) {
                return PROBE_IDE;
            }
            return PROBE_HTTP;
        } catch (Exception e) {
            return PROBE_NONE;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * Runs after the configured address fails. Sweeps this device's own /24 for
     * open IDE-ish ports, confirms over HTTP which are really editors, and — on
     * the first failure of a launch — connects to one without being asked.
     */
    private void beginAutoDiscovery(String lastError) {
        if (autoDiscoveryDone) {
            showManualEntry(lastError);
            return;
        }
        autoDiscoveryDone = true;
        pendingError = lastError;

        String prefix = subnetPrefixOf(localIpv4());
        if (prefix == null) {
            showManualEntry(lastError);
            return;
        }
        progressBar.setVisibility(View.VISIBLE);
        statusTitle.setText(R.string.scan_title);
        statusSubtitle.setText(R.string.scan_running);
        scanLan(prefix, !autoConnectUsed);
    }

    /** Manual entry point from the button; never auto-connects. */
    private void scanLanManually() {
        String prefix = subnetPrefixOf(localIpv4());
        if (prefix == null) {
            statusSubtitle.setText(R.string.scan_no_wifi);
            return;
        }
        btnScan.setEnabled(false);
        btnScan.setText(R.string.scan_running);
        scanLan(prefix, false);
    }

    /**
     * Two passes: a wide, cheap TCP sweep to find open ports, then an HTTP probe
     * of only the handful that answered. Short timeouts and wide fan-out keep
     * the whole thing to a few seconds.
     */
    private void scanLan(final String prefix, final boolean autoConnect) {
        final Set<String> open = Collections.synchronizedSet(new LinkedHashSet<String>());
        final AtomicInteger remaining = new AtomicInteger(254 * SCAN_PORTS.length);
        final ExecutorService pool = Executors.newFixedThreadPool(48);

        for (int i = 1; i <= 254; i++) {
            final String host = prefix + i;
            for (final int port : SCAN_PORTS) {
                pool.execute(() -> {
                    Socket sock = new Socket();
                    try {
                        sock.connect(new InetSocketAddress(host, port), 400);
                        open.add("http://" + host + ":" + port);
                    } catch (Exception ignored) {
                        // host silent on this port
                    } finally {
                        try { sock.close(); } catch (Exception ignored) { }
                        if (remaining.decrementAndGet() == 0) {
                            pool.shutdown();
                            classifyFound(open, autoConnect);
                        }
                    }
                });
            }
        }
    }

    /** Second pass: work out which open ports are actually editors. */
    private void classifyFound(final Set<String> open, final boolean autoConnect) {
        mainHandler.post(() -> statusSubtitle.setText(getString(R.string.scan_probing, open.size())));

        executor.execute(() -> {
            final List<String> ides = new ArrayList<>();
            final List<String> others = new ArrayList<>();
            for (String url : new ArrayList<>(open)) {
                int kind = probeServer(url, 1500);
                if (kind == PROBE_IDE) ides.add(url);
                else if (kind == PROBE_HTTP) others.add(url);
            }
            mainHandler.post(() -> finishScan(ides, others, autoConnect));
        });
    }

    private void finishScan(List<String> ides, List<String> others, boolean autoConnect) {
        if (btnScan != null) {
            btnScan.setEnabled(true);
            btnScan.setText(R.string.scan_wifi);
        }

        if (autoConnect && !ides.isEmpty()) {
            autoConnectUsed = true;
            String target = ides.get(0);
            statusTitle.setText(R.string.scan_title);
            statusSubtitle.setText(getString(R.string.scan_connecting, target));
            Log.i(TAG, "Auto-discovered IDE at " + target + " — connecting");
            currentServerUrl = target;
            prefs.edit().putString(KEY_SERVER_URL, target).apply();
            if (editServerUrl != null) editServerUrl.setText(target);
            isServerReady = false;
            pollServerReadiness();
            return;
        }

        showManualEntry(pendingError);
        if (recentsRow == null) return;
        recentsRow.removeAllViews();
        if (!ides.isEmpty()) {
            addRowLabel(getString(R.string.scan_found, ides.size()));
            for (String url : ides) addServerChoice(url);
        }
        if (!others.isEmpty()) {
            addRowLabel(getString(R.string.scan_other));
            for (String url : others) addServerChoice(url);
        }
        if (ides.isEmpty() && others.isEmpty()) {
            statusSubtitle.setText(R.string.scan_none);
        }
        for (String url : recentServers()) {
            if (!ides.contains(url) && !others.contains(url)) addServerChoice(url);
        }
    }

    private void showManualEntry(String lastError) {
        progressBar.setVisibility(View.GONE);
        statusTitle.setText(R.string.server_not_found_title);
        statusSubtitle.setText(getString(R.string.server_not_found_desc,
                currentServerUrl, lastError == null ? "" : lastError));
        serverConfigContainer.setVisibility(View.VISIBLE);
        editServerUrl.setText(currentServerUrl);
        renderRecents();
    }

    private void setupKeybar() {
        String[][] keyDefs = {
                {"ESC", "Escape"},
                {"TAB", "Tab"},
                {"CTRL", "Control"},
                {"ALT", "Alt"},
                {"{", "{"},
                {"}", "}"},
                {"(", "("},
                {")", ")"},
                {"[", "["},
                {"]", "]"},
                {";", ";"},
                {":", ":"},
                {"=", "="},
                {"\"", "\""},
                {"'", "'"},
                {"/", "/"},
                {"\\", "\\"},
                {"|", "|"},
                {"_", "_"},
                {"\u2190", "ArrowLeft"},
                {"\u2191", "ArrowUp"},
                {"\u2193", "ArrowDown"},
                {"\u2192", "ArrowRight"}
        };

        for (String[] def : keyDefs) {
            final String label = def[0];
            final String value = def[1];

            Button btn = new Button(this);
            btn.setText(label);
            btn.setTextColor(Color.parseColor("#CCCCCC"));
            btn.setTextSize(12f);
            btn.setBackgroundColor(Color.parseColor("#333333"));
            btn.setPadding(24, 0, 24, 0);
            btn.setGravity(Gravity.CENTER);

            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    dpToPx(36)
            );
            params.setMargins(dpToPx(3), 0, dpToPx(3), 0);
            btn.setLayoutParams(params);

            if (label.equals("CTRL")) {
                ctrlButton = btn;
            } else if (label.equals("ALT")) {
                altButton = btn;
            }

            btn.setOnClickListener(v -> handleKeyPress(label, value));
            keysContainer.addView(btn);
        }
    }

    private int getKeyCodeForSpecial(String value) {
        switch (value) {
            case "Escape": return 27;
            case "Tab": return 9;
            case "ArrowLeft": return 37;
            case "ArrowUp": return 38;
            case "ArrowRight": return 39;
            case "ArrowDown": return 40;
            default: return 0;
        }
    }

    private void handleKeyPress(String label, String value) {
        if ("CTRL".equals(label)) {
            isCtrlActive = !isCtrlActive;
            if (ctrlButton != null) {
                ctrlButton.setBackgroundColor(isCtrlActive ? Color.parseColor("#007ACC") : Color.parseColor("#333333"));
            }
            return;
        }

        if ("ALT".equals(label)) {
            isAltActive = !isAltActive;
            if (altButton != null) {
                altButton.setBackgroundColor(isAltActive ? Color.parseColor("#007ACC") : Color.parseColor("#333333"));
            }
            return;
        }

        // Send to active element in WebView
        boolean isPrintable = label.length() == 1 && !value.startsWith("Arrow") && !isCtrlActive && !isAltActive;
        String js;

        if (isPrintable) {
            String escaped = value.replace("\\", "\\\\").replace("\"", "\\\"");
            js = "(function() { " +
                    "var target = document.activeElement; " +
                    "if (!target || target === document.body) { " +
                    "  target = document.querySelector('.monaco-editor.focused textarea.inputarea') || " +
                    "           document.querySelector('.monaco-editor textarea.inputarea') || " +
                    "           document.querySelector('.terminal.xterm textarea.xterm-helper-textarea') || " +
                    "           document.querySelector('textarea, input:not([type=\"hidden\"])') || " +
                    "           document.body; " +
                    "} " +
                    "if (target && typeof target.focus === 'function' && document.activeElement !== target) { " +
                    "  try { target.focus(); } catch(e){} " +
                    "} " +
                    "if (typeof target.setRangeText === 'function') { " +
                    "  var start = target.selectionStart, end = target.selectionEnd; " +
                    "  target.setRangeText(\"" + escaped + "\", start, end, 'end'); " +
                    "  target.dispatchEvent(new InputEvent('input', { bubbles: true, inputType: 'insertText', data: \"" + escaped + "\" })); " +
                    "} else { " +
                    "  document.execCommand('insertText', false, \"" + escaped + "\"); " +
                    "} " +
                    "})();";
        } else {
            int keyCode = getKeyCodeForSpecial(value);
            js = "(function() { " +
                    "var target = document.activeElement; " +
                    "if (!target || target === document.body) { " +
                    "  target = document.querySelector('.monaco-editor.focused textarea.inputarea') || " +
                    "           document.querySelector('.monaco-editor textarea.inputarea') || " +
                    "           document.querySelector('.terminal.xterm textarea.xterm-helper-textarea') || " +
                    "           document.querySelector('textarea, input:not([type=\"hidden\"])') || " +
                    "           document.body; " +
                    "} " +
                    "if (target && typeof target.focus === 'function' && document.activeElement !== target) { " +
                    "  try { target.focus(); } catch(e){} " +
                    "} " +
                    "var opt = { key: '" + value + "', code: '" + value + "', keyCode: " + keyCode + ", which: " + keyCode + ", ctrlKey: " + isCtrlActive + ", altKey: " + isAltActive + ", bubbles: true, cancelable: true }; " +
                    "var downEvt = new KeyboardEvent('keydown', opt); " +
                    "try { Object.defineProperty(downEvt, 'keyCode', { get: function() { return " + keyCode + "; } }); Object.defineProperty(downEvt, 'which', { get: function() { return " + keyCode + "; } }); } catch(e){} " +
                    "target.dispatchEvent(downEvt); " +
                    "if ('" + value + "' === 'Tab' && typeof target.setRangeText === 'function') { " +
                    "  var start = target.selectionStart, end = target.selectionEnd; " +
                    "  target.setRangeText('    ', start, end, 'end'); " +
                    "  target.dispatchEvent(new InputEvent('input', { bubbles: true, inputType: 'insertText', data: '    ' })); " +
                    "} else if ('" + value + "' === 'ArrowLeft' && typeof target.setSelectionRange === 'function') { " +
                    "  var p = Math.max(0, target.selectionStart - 1); target.setSelectionRange(p, p); " +
                    "} else if ('" + value + "' === 'ArrowRight' && typeof target.setSelectionRange === 'function') { " +
                    "  var p = Math.min((target.value ? target.value.length : 0), target.selectionEnd + 1); target.setSelectionRange(p, p); " +
                    "} " +
                    "var upEvt = new KeyboardEvent('keyup', opt); " +
                    "try { Object.defineProperty(upEvt, 'keyCode', { get: function() { return " + keyCode + "; } }); Object.defineProperty(upEvt, 'which', { get: function() { return " + keyCode + "; } }); } catch(e){} " +
                    "target.dispatchEvent(upEvt); " +
                    "})();";
        }

        webView.evaluateJavascript(js, null);

        // Reset modifiers after regular keypress
        if (isCtrlActive) {
            isCtrlActive = false;
            if (ctrlButton != null) ctrlButton.setBackgroundColor(Color.parseColor("#333333"));
        }
        if (isAltActive) {
            isAltActive = false;
            if (altButton != null) altButton.setBackgroundColor(Color.parseColor("#333333"));
        }
    }

    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            // Minimize rather than killing background server
            moveTaskToBack(true);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
        if (isServiceStarted) {
            stopService(new Intent(this, VScodeService.class));
            isServiceStarted = false;
        }
    }
}
