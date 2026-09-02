package com.binbin.pibridge;

import android.app.Activity;
import android.content.Intent;
import android.view.MotionEvent;
import android.view.View;
import android.content.Intent;
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

import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

import java.io.File;

/** 小丘原生壳：🎙语音（注入热会话）+ 对话/场景/工具/设置 四页。 */
public class MainActivity extends Activity {
    private WebView web;
    private TextView splash;
    private LinearLayout voiceStrip;
    private TextView voiceMsg;
    private Button voiceCancel;
    private Button voiceSend;
    private Button micBtn;
    private String pendingHeard = "";
    private final java.util.concurrent.atomic.AtomicBoolean stopFlag = new java.util.concurrent.atomic.AtomicBoolean(false);
    private Button[] btns;
    private int currentTab = 0;
    private int retries = 0;
    private volatile boolean recording = false;

    private static final String[] TABS = {"💬 对话", "⚡ 场景", "🔧 工具", "⚙ 设置"};
    private static final String[] URLS = {
            "http://127.0.0.1:8182",
            "http://127.0.0.1:8181/#/scenes",
            "http://127.0.0.1:8181/#/tools",
            "http://127.0.0.1:8181/#/settings"
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
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        web.setWebViewClient(new WebViewClient() {
            @Override public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
                if (r.isForMainFrame()) scheduleRetry();
            }
        });
        page.addView(web, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        voiceStrip = new LinearLayout(this);
        voiceStrip.setOrientation(LinearLayout.HORIZONTAL);
        voiceStrip.setGravity(Gravity.CENTER_VERTICAL);
        voiceStrip.setBackgroundColor(Color.parseColor("#3E7C59"));
        voiceStrip.setPadding(28, 16, 28, 16);
        voiceStrip.setVisibility(View.GONE);
        voiceMsg = new TextView(this);
        voiceMsg.setTextColor(Color.WHITE);
        voiceMsg.setTextSize(14);
        voiceMsg.setPadding(0, 0, 16, 0);
        voiceStrip.addView(voiceMsg, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        voiceCancel = new Button(this);
        voiceCancel.setText("取消");
        voiceCancel.setTextSize(13);
        voiceCancel.setTextColor(Color.WHITE);
        voiceCancel.setBackgroundColor(Color.parseColor("#5F8A73"));
        voiceCancel.setPadding(20, 8, 20, 8);
        voiceCancel.setOnClickListener(v -> { voiceStrip.setVisibility(View.GONE); });
        voiceStrip.addView(voiceCancel, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        voiceSend = new Button(this);
        voiceSend.setText("发送");
        voiceSend.setTextSize(13);
        voiceSend.setTextColor(Color.WHITE);
        voiceSend.setBackgroundColor(Color.parseColor("#E8853D"));
        voiceSend.setPadding(20, 8, 20, 8);
        voiceSend.setOnClickListener(v -> { voiceStrip.setVisibility(View.GONE); injectPrompt(pendingHeard); });
        voiceStrip.addView(voiceSend, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        page.addView(voiceStrip, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding(6, 6, 6, 6);
        // 🎙 语音按钮（大）
        micBtn = new Button(this);
        micBtn.setText("🎙");
        micBtn.setBackgroundColor(Color.parseColor("#3E7C59"));
        micBtn.setTextColor(Color.WHITE);
        micBtn.setOnTouchListener((v, ev) -> {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (!recording) startVoiceCapture();
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (recording) stopVoiceCapture();
                    return true;
            }
            return false;
        });
        micBtn.setTextSize(18);
        bar.addView(micBtn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.9f));
        btns = new Button[TABS.length];
        for (int i = 0; i < TABS.length; i++) {
            final int idx = i;
            Button btn = new Button(this);
            btn.setText(TABS[i]);
            btn.setBackgroundColor(Color.TRANSPARENT);
            btn.setTextColor(i == 0 ? ACTIVE : IDLE);
            btn.setOnClickListener(v -> switchTab(idx));
            btns[i] = btn;
            bar.addView(btn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.1f));
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
        splash.setVisibility(View.GONE);
        root.addView(splash, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(root);
    }

    private void switchTab(int idx) {
        currentTab = idx;
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

    // ═══ 🎙 语音流：VAD 录音 → 识别 → 注入对话页输入框自动发送 ═══
    private void startVoiceCapture() {
        recording = true;
        if (currentTab != 0) switchTab(0);
        voiceStrip.setVisibility(View.VISIBLE);
        voiceMsg.setText("🎙 录音中…松手结束");
        voiceCancel.setVisibility(View.GONE);
        voiceSend.setVisibility(View.GONE);
        micBtn.setText("⏺ 松手");
        micBtn.setBackgroundColor(Color.parseColor("#C24B3C"));
        stopFlag.set(false);
        new Thread(() -> {
            try {
                File wav = WavUtil.recordUntil(this, stopFlag, 60);
                voiceMsg("识别中…");
                JSONObject sttEnv = Tools.call("stt_transcribe",
                        new JSONObject().put("file", wav.getAbsolutePath()));
                String heard = sttEnv.optString("data", "").trim();
                final String h = heard;
                runOnUiThread(() -> {
                    recording = false;
                    micBtn.setText("🎙");
                    micBtn.setBackgroundColor(Color.parseColor("#3E7C59"));
                    if (h.isEmpty() || h.startsWith("(")) {
                        voiceMsg("没听清，再试一次");
                        voiceStrip.postDelayed(() -> voiceStrip.setVisibility(View.GONE), 1800);
                    } else {
                        pendingHeard = h;
                        voiceMsg("🗣 " + h);
                        voiceCancel.setVisibility(View.VISIBLE);
                        voiceSend.setVisibility(View.VISIBLE);
                    }
                });
            } catch (final Exception e) {
                runOnUiThread(() -> {
                    recording = false;
                    micBtn.setText("🎙");
                    micBtn.setBackgroundColor(Color.parseColor("#3E7C59"));
                    voiceMsg("🎙 出错：" + e.getMessage());
                    voiceStrip.postDelayed(() -> voiceStrip.setVisibility(View.GONE), 2500);
                });
            }
        }, "voice-hold").start();
    }

    private void stopVoiceCapture() { stopFlag.set(true); }

    private void voiceMsg(String msg) {
        runOnUiThread(() -> { voiceMsg.setText(msg); voiceStrip.setVisibility(View.VISIBLE); });
    }

    private void injectPrompt(String text) {
        if (currentTab != 0) switchTab(0);
        final String[] attempt = {0 + ""};
        web.postDelayed(new Runnable() {
            int tries = 0;
            @Override public void run() {
                String js = "(function(){const ta=document.querySelector('textarea');if(!ta)return 'NO_TA';" +
                        "const set=Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype,'value').set;" +
                        "set.call(ta," + JSONObject.quote(text) + ");" +
                        "ta.dispatchEvent(new Event('input',{bubbles:true}));ta.focus();" +
                        "ta.dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',code:'Enter',keyCode:13,bubbles:true}));" +
                        "return 'OK';})()";
                web.evaluateJavascript(js, r -> {
                    String res = r != null ? r.replace("\"", "") : "";
                    if (res.contains("OK")) return;
                    if (tries++ < 5) web.postDelayed(this, 1200);
                });
            }
        }, 1200);
    }

    /** 向 pi-web-ui 输入框注入文本并回车发送（React 受控组件安全写法） */
    

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
