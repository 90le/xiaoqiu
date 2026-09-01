package com.binbin.pibridge;

import android.app.Activity;
import android.content.Intent;
import android.view.View;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** 小丘原生壳：先等服务就绪（遮罩），失败自动重试——绝不给用户糊错误页 */
public class MainActivity extends Activity {
    private WebView web;
    private TextView splash;
    private Button[] btns;
    private int currentTab = 0;
    private int retries = 0;
    private volatile String loadingBase = "";

    private static final String[] TABS = {"💬 对话", "⚡ 场景", "🔧 工具", "⚙ 设置"};
    private static final String[] URLS = {
            "http://127.0.0.1:8182",
            "http://127.0.0.1:8181/scenes.html",
            "http://127.0.0.1:8181/tools.html",
            "http://127.0.0.1:8181/settings.html"
    };
    private static final int ACTIVE = 0xFF3E7C59;
    private static final int IDLE = 0xFF7A8471;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        startService(new Intent(this, BridgeService.class));
        buildUi();
        switchTab(0);
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(Color.WHITE);

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        web.setWebViewClient(new WebViewClient() {
            @Override public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
                if (r.isForMainFrame()) scheduleRetry();
            }
        });
        page.addView(web, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding(8, 8, 8, 8);
        btns = new Button[TABS.length];
        for (int i = 0; i < TABS.length; i++) {
            final int idx = i;
            Button btn = new Button(this);
            btn.setText(TABS[i]);
            btn.setBackgroundColor(Color.TRANSPARENT);
            btn.setTextColor(i == 0 ? ACTIVE : IDLE);
            btn.setOnClickListener(v -> switchTab(idx));
            btns[i] = btn;
            bar.addView(btn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        page.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(page, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        splash = new TextView(this);
        splash.setText("🏔\n小丘启动中…");
        splash.setTextSize(20);
        splash.setLineSpacing(8, 1);
        splash.setGravity(Gravity.CENTER);
        splash.setTextColor(Color.parseColor("#3E7C59"));
        splash.setBackgroundColor(Color.parseColor("#F7F3EC"));
        root.addView(splash, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(root);
    }

    private void switchTab(int idx) {
        currentTab = idx;
        retries = 0;
        for (int j = 0; j < btns.length; j++) btns[j].setTextColor(j == idx ? ACTIVE : IDLE);
        final String url = URLS[idx];
        int slash = url.indexOf('/', 7);
        final String base = slash > 0 ? url.substring(0, slash) : url;
        splash.setText("🏔\n小丘启动中…");
        splash.setVisibility(View.VISIBLE);
        new Thread(() -> {
            boolean ok = waitUp(base + "/ping", 20) || waitUp(base + "/", 15);
            runOnUiThread(() -> {
                if (currentTab != idx) return;
                if (ok) {
                    splash.setVisibility(View.GONE);
                    web.loadUrl(url);
                } else {
                    splash.setText("🏔\n小丘服务准备中\n将持续重试…");
                    retryLater();
                }
            });
        }, "tab-load").start();
    }

    private void scheduleRetry() {
        runOnUiThread(() -> {
            splash.setText("🏔\n小丘服务重启中\n将持续重试…");
            splash.setVisibility(View.VISIBLE);
            retryLater();
        });
    }

    private void retryLater() {
        retries++;
        if (retries > 30) {
            splash.setText("🏔\n多次重试未成功\n请稍后点击当前页签再试");
            return;
        }
        web.postDelayed(() -> switchTab(currentTab), 3000);
    }

    private boolean waitUp(String url, int sec) {
        long deadline = System.currentTimeMillis() + sec * 1000L;
        while (System.currentTimeMillis() < deadline) {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(1200);
                c.setReadTimeout(1200);
                if (c.getResponseCode() == 200) return true;
            } catch (Exception ignore) {}
            try { Thread.sleep(1000); } catch (InterruptedException ignore) {}
        }
        return false;
    }

    @Override public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }
}
