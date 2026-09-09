package com.openvscode.mobile;

import android.annotation.SuppressLint;
import android.content.Intent;
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
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private static final String SERVER_URL = "http://127.0.0.1:8080";

    private WebView webView;
    private View loadingOverlay;
    private TextView statusTitle;
    private Button btnRetry;
    private LinearLayout keysContainer;

    private boolean isCtrlActive = false;
    private boolean isAltActive = false;
    private Button ctrlButton;
    private Button altButton;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private boolean isServerReady = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webView);
        loadingOverlay = findViewById(R.id.loadingOverlay);
        statusTitle = findViewById(R.id.statusTitle);
        btnRetry = findViewById(R.id.btnRetry);
        keysContainer = findViewById(R.id.keysContainer);

        // Start background foreground service (keeps CPU awake)
        startBackgroundService();

        // Setup WebView settings
        initWebView();

        // Populate bottom coding touchbar
        setupKeybar();

        // Retry button listener
        btnRetry.setOnClickListener(v -> {
            btnRetry.setVisibility(View.GONE);
            statusTitle.setText(R.string.server_starting);
            pollServerReadiness();
        });

        // Begin polling localhost server
        pollServerReadiness();
    }

    private void startBackgroundService() {
        Intent serviceIntent = new Intent(this, VScodeService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(this, serviceIntent);
        } else {
            startService(serviceIntent);
        }
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
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (url.startsWith("http://127.0.0.1:8080") || url.startsWith("http://localhost:8080")) {
                    loadingOverlay.animate().alpha(0f).setDuration(300).withEndAction(() -> {
                        loadingOverlay.setVisibility(View.GONE);
                    });
                }
            }
        });
    }

    private void pollServerReadiness() {
        executor.execute(() -> {
            int attempts = 0;
            while (!isServerReady && attempts < 30) {
                attempts++;
                try {
                    HttpURLConnection conn = (HttpURLConnection) new URL(SERVER_URL).openConnection();
                    conn.setConnectTimeout(1500);
                    conn.setReadTimeout(1500);
                    conn.setRequestMethod("GET");
                    int code = conn.getResponseCode();
                    conn.disconnect();

                    if (code == 200 || code == 302 || code == 401 || code == 403) {
                        isServerReady = true;
                        mainHandler.post(() -> {
                            Log.i(TAG, "Server reached. Loading into WebView...");
                            webView.loadUrl(SERVER_URL);
                        });
                        return;
                    }
                } catch (Exception ignored) {
                    // Server still booting up
                }

                try {
                    Thread.sleep(1500);
                } catch (InterruptedException e) {
                    break;
                }
            }

            if (!isServerReady) {
                mainHandler.post(() -> {
                    statusTitle.setText("Waiting for Server...");
                    btnRetry.setVisibility(View.VISIBLE);
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
                    "var target = document.activeElement || document.body; " +
                    "if (typeof target.setRangeText === 'function') { " +
                    "  var start = target.selectionStart, end = target.selectionEnd; " +
                    "  target.setRangeText(\"" + escaped + "\", start, end, 'end'); " +
                    "  target.dispatchEvent(new InputEvent('input', { bubbles: true, inputType: 'insertText', data: \"" + escaped + "\" })); " +
                    "} else { " +
                    "  document.execCommand('insertText', false, \"" + escaped + "\"); " +
                    "} " +
                    "})();";
        } else {
            js = "(function() { " +
                    "var target = document.activeElement || document.body; " +
                    "var opt = { key: '" + value + "', code: '" + value + "', ctrlKey: " + isCtrlActive + ", altKey: " + isAltActive + ", bubbles: true }; " +
                    "target.dispatchEvent(new KeyboardEvent('keydown', opt)); " +
                    "if ('" + value + "' === 'Tab' && typeof target.setRangeText === 'function') { " +
                    "  var start = target.selectionStart, end = target.selectionEnd; " +
                    "  target.setRangeText('    ', start, end, 'end'); " +
                    "  target.dispatchEvent(new InputEvent('input', { bubbles: true, inputType: 'insertText', data: '    ' })); " +
                    "} else if ('" + value + "' === 'ArrowLeft' && typeof target.setSelectionRange === 'function') { " +
                    "  var p = Math.max(0, target.selectionStart - 1); target.setSelectionRange(p, p); " +
                    "} else if ('" + value + "' === 'ArrowRight' && typeof target.setSelectionRange === 'function') { " +
                    "  var p = Math.min(target.value.length, target.selectionEnd + 1); target.setSelectionRange(p, p); " +
                    "} " +
                    "target.dispatchEvent(new KeyboardEvent('keyup', opt)); " +
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
        executor.shutdown();
    }
}
