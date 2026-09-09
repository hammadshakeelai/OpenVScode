package com.openvscode.mobile;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.SharedPreferences;
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

import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private static final String PREFS_NAME = "openvscode_prefs";
    private static final String KEY_SERVER_URL = "server_url";
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

        // Request runtime notification permission on Android 13+
        requestNotificationPermission();

        // Setup WebView settings
        initWebView();

        // Populate bottom coding touchbar
        setupKeybar();

        // Connect button listener (saves URL and retries)
        btnConnect.setOnClickListener(v -> {
            String inputUrl = editServerUrl.getText().toString().trim();
            if (!inputUrl.isEmpty()) {
                if (!inputUrl.startsWith("http://") && !inputUrl.startsWith("https://")) {
                    inputUrl = "http://" + inputUrl;
                }
                if (inputUrl.endsWith("/")) {
                    inputUrl = inputUrl.substring(0, inputUrl.length() - 1);
                }
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
        pollServerReadiness();
    }

    private void startBackgroundService() {
        if (isServiceStarted) return;
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
                if (url != null && isMatchingServer(url, currentServerUrl)) {
                    Log.i(TAG, "Server matched! Dismissing loadingOverlay.");
                    loadingOverlay.animate().alpha(0f).setDuration(300).withEndAction(() -> {
                        loadingOverlay.setVisibility(View.GONE);
                    });
                    // Start background wake lock service only after server actually loads
                    startBackgroundService();
                } else {
                    Log.w(TAG, "onPageFinished url did not match: url=" + url + " server=" + currentServerUrl);
                }
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, android.webkit.WebResourceError error) {
                super.onReceivedError(view, request, error);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    Log.e(TAG, "WebView onReceivedError: " + error.getDescription() + " code=" + error.getErrorCode() + " url=" + request.getUrl());
                }
            }
        });
    }

    private boolean isMatchingServer(String pageUrl, String serverUrl) {
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
            while (!isServerReady && attempts < 15) {
                attempts++;
                final int currentAttempt = attempts;
                mainHandler.post(() -> {
                    if (!isServerReady) {
                        statusSubtitle.setText("Attempt " + currentAttempt + "/15 — Connecting to " + currentServerUrl);
                    }
                });

                try {
                    HttpURLConnection conn = (HttpURLConnection) new URL(currentServerUrl).openConnection();
                    conn.setConnectTimeout(1500);
                    conn.setReadTimeout(1500);
                    conn.setRequestMethod("GET");
                    int code = conn.getResponseCode();
                    conn.disconnect();

                    if (code == 200 || code == 302 || code == 401 || code == 403) {
                        isServerReady = true;
                        mainHandler.post(() -> {
                            Log.i(TAG, "Server reached. Loading into WebView: " + currentServerUrl);
                            webView.loadUrl(currentServerUrl);
                        });
                        return;
                    }
                } catch (Exception e) {
                    lastError = e.getClass().getSimpleName() + ": " + (e.getMessage() != null ? e.getMessage() : "Connection refused");
                    Log.w(TAG, "Connection attempt " + currentAttempt + " to " + currentServerUrl + " failed: " + lastError);
                }

                try {
                    Thread.sleep(1200);
                } catch (InterruptedException e) {
                    break;
                }
            }

            if (!isServerReady) {
                final String finalError = lastError;
                mainHandler.post(() -> {
                    progressBar.setVisibility(View.GONE);
                    statusTitle.setText(R.string.server_not_found_title);
                    statusSubtitle.setText(getString(R.string.server_not_found_desc, currentServerUrl, finalError));
                    serverConfigContainer.setVisibility(View.VISIBLE);
                    editServerUrl.setText(currentServerUrl);
                });
            }
        });
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
