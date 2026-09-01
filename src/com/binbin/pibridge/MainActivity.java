package com.binbin.pibridge;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebSettings;

public class MainActivity extends Activity {
    private WebView wv;
    private static final String UI = "file:///android_asset/console.html";

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        try { startForegroundService(new Intent(this, BridgeService.class)); } catch (Exception ignore) {}
        wv = new WebView(this);
        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowUniversalAccessFromFileURLs(true);
        wv.setWebViewClient(new WebViewClient());
        wv.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        setContentView(wv);
        Intent in = getIntent();
        String rp = in == null ? null : in.getStringExtra("req_perm");
        if (rp != null && rp.length() > 0) {
            requestPermissions(new String[]{rp}, 1);
        }
        wv.loadUrl(UI);
    }

    @Override public void onBackPressed() {
        if (wv != null && wv.canGoBack()) wv.goBack();
        else super.onBackPressed();
    }

    @Override protected void onDestroy() {
        if (wv != null) wv.destroy();
        super.onDestroy();
    }
}
