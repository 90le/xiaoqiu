package com.binbin.pibridge;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;
import org.json.JSONObject;
import java.io.File;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 小丘主界面：单一 WebView 承载系统级工作台（Vue）。
 * 原生只保留三件事：服务拉起 / 本地语音识别桥（对话页麦克风）/ 悬浮球交接。
 */
public class MainActivity extends Activity {
    public static volatile boolean PENDING_CONVO = false; // 悬浮球长按：进入连续对话
    public static volatile String PENDING_TASK = null;    // 悬浮球/唤醒：任务交接给对话页

    private WebView web;
    private TextView splash;
    private final java.util.concurrent.atomic.AtomicBoolean stopFlag =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private volatile boolean recording = false;
    private int retries = 0;
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        startService(new Intent(this, BridgeService.class));
        buildUi();
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        web.setBackgroundColor(Color.parseColor("#F7F3EC"));
        web.setWebViewClient(new WebViewClient() {
            @Override public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
                if (r.isForMainFrame()) scheduleRetry();
            }
        });
        web.addJavascriptInterface(new JsBridge(), "XiaoqiuBridge");
        web.loadUrl("http://127.0.0.1:8181/#chat");
        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        splash = new TextView(this);
        splash.setText("🏔\n小丘启动中…");
        splash.setTextSize(20);
        splash.setLineSpacing(8, 1);
        splash.setGravity(android.view.Gravity.CENTER);
        splash.setTextColor(Color.parseColor("#3E7C59"));
        splash.setBackgroundColor(Color.parseColor("#F7F3EC"));
        root.addView(splash, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(root);
        waitThenReload();
    }

    /** 服务就绪前轮询重载（首页加载失败时由 onReceivedError 触发） */
    private void waitThenReload() {
        new Thread(() -> {
            boolean up = waitUp("http://127.0.0.1:8181/ping", 20) || waitUp("http://127.0.0.1:8181/", 10);
            if (up) ui.post(() -> {
                splash.setVisibility(View.GONE);
                web.loadUrl("http://127.0.0.1:8181/#chat");
            });
            else ui.post(() -> {
                splash.setText("🏔\n小丘服务准备中\n将持续重试…");
                scheduleRetry();
            });
        }, "first-load").start();
    }

    private void scheduleRetry() {
        retries++;
        if (retries > 12) return;
        ui.postDelayed(() -> web.loadUrl("http://127.0.0.1:8181/#chat"), 4000L * retries);
    }

    /** 网页 → 原生：对话页麦克风（本地 VoiceCore/WavUtil 识别，免云端、免网页录音权限） */
    private class JsBridge {
        @JavascriptInterface public boolean startVoice() {
            if (recording) return false;
            ui.post(() -> pageVoice(true));
            return true;
        }
        @JavascriptInterface public void stopVoice() {
            ui.post(() -> pageVoice(false));
        }
    }

    /** 页面语音：录音→本地识别→结果派发回页面（快慢脑分流在页面里做，UI 状态实时推） */
    private void pageVoice(boolean start) {
        if (start) {
            if (recording) return;
            recording = true;
            Tools.micBusy = true;
            stopFlag.set(false);
            js("window.__voiceStatus && window.__voiceStatus('recording')");
            new Thread(() -> {
                try {
                    File wav = WavUtil.recordUntil(this, stopFlag, 60);
                    js("window.__voiceStatus && window.__voiceStatus('thinking')");
                    JSONObject sttEnv = Tools.call("stt_transcribe",
                            new JSONObject().put("file", wav.getAbsolutePath()));
                    String heard = sttEnv.optString("data", "").trim();
                    recording = false;
                    Tools.micBusy = false;
                    if (heard.isEmpty() || heard.startsWith("(")) {
                        js("window.__voiceStatus && window.__voiceStatus('empty')");
                    } else {
                        js("window.__voiceResult && window.__voiceResult(" + JSONObject.quote(heard) + ")");
                    }
                } catch (Exception e) {
                    recording = false;
                    Tools.micBusy = false;
                    js("window.__voiceStatus && window.__voiceStatus('error:" + e.getMessage() + "')");
                }
            }, "page-voice").start();
        } else {
            stopFlag.set(true);
        }
    }

    private void js(String code) {
        ui.post(() -> { try { web.evaluateJavascript(code, null); } catch (Exception ignore) {} });
    }

    /** 连续对话（悬浮球长按/唤醒进入）：VoiceCore 引擎，状态实时推给页面 */
    public void startConvo() {
        if (VoiceCore.running) return;
        VoiceCore.nextStandby = false; // App 内=对话模式（无需唤醒词）
        VoiceCore.start(new VoiceCore.Listener() {
            public void onState(String st, String info) {
                String txt = st.equals("listen") ? "🎧 请说…"
                        : st.equals("think") ? "🗣 " + info
                        : st.equals("speak") ? "🔊 " + info
                        : (info == null || info.isEmpty() ? "对话结束" : info);
                js("window.__convoState && window.__convoState(" + JSONObject.quote(st + "|" + txt) + ")");
            }
            public void onTask(String q) {
                js("location.hash='#chat'");
                dispatchTask(q, false);
            }
            public Context ctx() { return MainActivity.this; }
        });
    }

    /** 任务派发进对话页（悬浮球/唤醒/连续对话共用）；带重试等页面挂载 */
    private void dispatchTask(String q, boolean speakReply) {
        final int[] tries = {0};
        Runnable[] r = new Runnable[1];
        r[0] = () -> {
            String js = "(window.__xiaoqiuTask?" +
                    "(window.__xiaoqiuTask(" + JSONObject.quote(q) + "," + (speakReply) + "),'OK'):'NO')";
            web.evaluateJavascript(js, v -> {
                String res = v == null ? "" : v.replace("\"", "");
                if (!res.contains("OK") && tries[0]++ < 15) ui.postDelayed(r[0], 700);
            });
        };
        ui.post(r[0]);
    }

    @Override protected void onResume() {
        super.onResume();
        if (PENDING_CONVO) {
            PENDING_CONVO = false;
            js("location.hash='#chat'");
            ui.postDelayed(() -> startConvo(), 400);
        }
        if (PENDING_TASK != null) {
            String q = PENDING_TASK;
            PENDING_TASK = null;
            js("location.hash='#chat'");
            ui.postDelayed(() -> dispatchTask(q, true), 300);
        }
    }

    @Override public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
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
}
