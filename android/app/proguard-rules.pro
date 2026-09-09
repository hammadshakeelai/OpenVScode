# Keep the WebView JS bridge and service entry points reachable after shrinking.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
