package com.waters.radio;

import android.app.Activity;
import android.os.Bundle;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/**
 * WATERS RADIO —— 用系统 WebView 加载本地单屏 HTML（含 ?auto=1 自动播放首台 WLTW）。
 * 所有电台逻辑、多源热备、设置后台均在 HTML 内实现，原生层只负责承载与权限。
 */
public class MainActivity extends Activity {

    private WebView webView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        webView = new WebView(this);
        setContentView(webView);

        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        // 允许媒体自动播放（首次打开即播 WLTW，无需手势）
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setAllowFileAccess(true);
        ws.setAllowContentAccess(true);
        ws.setDomStorageEnabled(true);
        ws.setDatabaseEnabled(true);
        ws.setLoadsImagesAutomatically(true);
        // 允许混合内容（部分电台流为 HTTP）
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        ws.setCacheMode(WebSettings.LOAD_DEFAULT);

        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient());

        // 加载本地资产，并带上 auto=1 触发自动播放首台
        webView.loadUrl("file:///android_asset/index.html?auto=1");
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onPause() {
        // 不主动暂停 WebView，尽量保持后台音频继续播放
        super.onPause();
    }
}
