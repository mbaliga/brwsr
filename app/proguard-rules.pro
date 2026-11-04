# JavaScript bridge methods are called reflectively by the WebView.
-keepclassmembers class dev.aarso.brwsr.web.JsBridge {
    @android.webkit.JavascriptInterface <methods>;
}
