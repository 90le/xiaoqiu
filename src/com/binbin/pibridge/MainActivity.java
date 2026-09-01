package com.binbin.pibridge;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.ViewGroup;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;

/** 小丘原生壳：底部导航（对话=pi-web-ui:8182 / 工具=MCP控制台:8181 / 设置=填Key页） */
public class MainActivity extends Activity {
    private WebView web;
    private Button[] btns;
    private static final String[] TABS = {"💬 对话", "🔧 工具", "⚙ 设置"};
    private static final String[] URLS = {
            "http://127.0.0.1:8182",
            "http://127.0.0.1:8181",
            "http://127.0.0.1:8181/settings.html"
    };
    private static final int ACTIVE = 0xFF3E7C59;
    private static final int IDLE = 0xFF7A8471;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        startService(new Intent(this, BridgeService.class));
        buildUi();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        web.setWebViewClient(new WebViewClient());
        web.setBackgroundColor(Color.WHITE);
        root.addView(web, new LinearLayout.LayoutParams(
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
            btn.setOnClickListener(v -> {
                web.loadUrl(URLS[idx]);
                for (int j = 0; j < btns.length; j++) btns[j].setTextColor(j == idx ? ACTIVE : IDLE);
            });
            btns[i] = btn;
            bar.addView(btn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        root.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(root);
        web.loadUrl(URLS[0]);
    }

    @Override public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }
}
