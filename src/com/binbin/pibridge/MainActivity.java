package com.binbin.pibridge;

import android.util.Log;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.view.MotionEvent;
import android.view.View;
import android.content.Context;
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
    private int voiceGen = 0; // 语音播报代次：新发送使旧监视器失效
    // ── 连续对话（引擎在 VoiceCore，本类只做 UI 映射）──
    public static volatile boolean PENDING_CONVO = false; // 悬浮球长按触发
    public static volatile String PENDING_TASK = null;    // 悬浮球对话中的任务交接
    private final java.util.concurrent.atomic.AtomicBoolean stopFlag = new java.util.concurrent.atomic.AtomicBoolean(false);
    private Button[] btns;
    private WebView chatWeb; // 对话（pi-web-ui:8182，注入textarea）
    private int currentTab = 0;
    private int retries = 0;
    private volatile boolean recording = false;

    private static final String[] TABS = {"💬 对话", "🏠 工作台"};
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

        chatWeb = new WebView(this);
        WebSettings cs = chatWeb.getSettings();
        cs.setJavaScriptEnabled(true);
        cs.setDomStorageEnabled(true);
        cs.setCacheMode(WebSettings.LOAD_NO_CACHE);
        chatWeb.loadUrl("http://127.0.0.1:8182");

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
        page.addView(chatWeb, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        page.addView(web, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        web.loadUrl("http://127.0.0.1:8181/");
        chatWeb.setVisibility(View.GONE); // 默认显示工作台

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
        voiceCancel.setOnClickListener(v -> { VoiceCore.stop(); voiceStrip.setVisibility(View.GONE); });
        voiceStrip.addView(voiceCancel, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        voiceSend = new Button(this);
        voiceSend.setText("发送");
        voiceSend.setTextSize(13);
        voiceSend.setTextColor(Color.WHITE);
        voiceSend.setBackgroundColor(Color.parseColor("#E8853D"));
        voiceSend.setPadding(20, 8, 20, 8);
        voiceSend.setOnClickListener(v -> { VoiceCore.stop(); voiceStrip.setVisibility(View.GONE); fastOrSlow(pendingHeard); });
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
        chatWeb.setVisibility(idx == 0 ? View.VISIBLE : View.GONE);
        web.setVisibility(idx == 0 ? View.GONE : View.VISIBLE);
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
        Tools.micBusy = true;
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
                    Tools.micBusy = false;
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
                Tools.micBusy = false;
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

    /** 两脑分流：快脑能答秒答+朗读；任务才交给慢脑(pi) */
    private void fastOrSlow(final String q) {
        voiceMsg("🤔 想想…");
        voiceStrip.setVisibility(View.VISIBLE);
        new Thread(() -> {
            String answer = null;
            try {
                JSONObject env = Tools.call("chat_fast", new JSONObject().put("q", q));
                if (env.optBoolean("ok")) {
                    JSONObject d = env.optJSONObject("data");
                    if (d != null && "chat".equals(d.optString("type"))) answer = d.optString("answer", "");
                }
            } catch (Exception ignored) {}
            final String fAns = answer;
            runOnUiThread(() -> {
                if (fAns == null || fAns.isEmpty()) {
                    injectPrompt(q, true); // 慢脑接管 + 完成播报
                } else {
                    pendingHeard = fAns;
                    voiceMsg("💬 " + fAns);
                    new Thread(() -> { try { Tools.call("tts_speak", new JSONObject().put("text", fAns)); } catch (Exception ignored) {} }, "tts-fast").start();
                    voiceStrip.postDelayed(() -> voiceStrip.setVisibility(View.GONE), 10000);
                }
            });
        }, "fast-brain").start();
    }

    private void startConvo() {
        if (VoiceCore.running) return;
        if (currentTab != 0) switchTab(0);
        voiceCancel.setVisibility(View.VISIBLE);
        VoiceCore.nextStandby = false; // App 内=对话模式（无需唤醒词）
        VoiceCore.start(new VoiceCore.Listener() {
            public void onState(String st, String info) {
                switch (st) {
                    case "listen": voiceMsg("🎧 请说…"); break;
                    case "think": voiceMsg("🗣 " + info); break;
                    case "speak": voiceMsg("🔊 " + info); break;
                    case "exit": voiceMsg(info == null || info.isEmpty() ? "对话结束" : info);
                        voiceStrip.postDelayed(() -> voiceStrip.setVisibility(View.GONE), 2500); break;
                    default: break;
                }
            }
            public void onTask(String q) { injectPrompt(q, true); }
            public Context ctx() { return MainActivity.this; }
        });
    }

    private void voiceMsg(String msg) {
        runOnUiThread(() -> { voiceMsg.setText(msg); voiceStrip.setVisibility(View.VISIBLE); });
    }

    private void injectPrompt(String text) { injectPrompt(text, false); }

    private void injectPrompt(String text, boolean speakReply) {
        if (currentTab != 0) switchTab(0);
        final int gen = ++voiceGen;
        if (speakReply) watchReplyAndSpeak(gen, text);
        final String[] attempt = {0 + ""};
        chatWeb.postDelayed(new Runnable() {
            int tries = 0;
            @Override public void run() {
                String js = "(function(){const ta=document.querySelector('textarea');if(!ta)return 'NO_TA';" +
                        "const set=Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype,'value').set;" +
                        "set.call(ta," + JSONObject.quote(text) + ");" +
                        "ta.dispatchEvent(new Event('input',{bubbles:true}));ta.focus();" +
                        "ta.dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',code:'Enter',keyCode:13,bubbles:true}));" +
                        "return 'OK';})()";
                chatWeb.evaluateJavascript(js, r -> {
                    String res = r != null ? r.replace("\"", "") : "";
                    if (res.contains("OK")) return;
                    if (tries++ < 5) chatWeb.postDelayed(this, 1200);
                });
            }
        }, 1200);
    }

    /** 监视对话页最新消息：检测「进行中…」消失+文本稳定后立即 TTS 播报 */
    private void watchReplyAndSpeak(final int gen, final String sentText) {
        final int[] polls = {0};
        final int[] stable = {0};
        final String[] last = {""};
        final boolean[] busySeen = {false};
        final int[] initCount = {-1};
        chatWeb.postDelayed(new Runnable() {
            @Override public void run() {
                if (gen != voiceGen || polls[0]++ > 300) return; // 新发送/超时(~5min)终止
                chatWeb.evaluateJavascript(
                    "(function(){var busy=document.body.innerText.indexOf('进行中…')>=0;"
                  + "var n=document.querySelectorAll('.msg-text,.fp-markdown,[class*=markdown]');"
                  + "if(!n.length)return (busy?'B1':'B0')+'|N0';"
                  + "var last=n[n.length-1];"
                  + "return (busy?'B1':'B0')+'|'+n.length+'|'+(last.innerText||last.textContent||'').slice(0,600);})()",
                    v -> {
                        if (gen != voiceGen) return;
                        String t = v == null ? "" : v;
                        if (t.length() > 1 && t.startsWith("\"") && t.endsWith("\""))
                            t = t.substring(1, t.length() - 1);
                        t = t.replace("\\n", " ").replace("\\\"", "\"").trim();
                        boolean busy = t.startsWith("B1");
                        String rest = t.length() > 3 ? t.substring(3) : "";
                        int cnt = -1;
                        String body = "";
                        int p2 = rest.indexOf('|');
                        if (p2 > 0) {
                            try { cnt = Integer.parseInt(rest.substring(0, p2)); } catch (Exception ignore) {}
                            body = rest.substring(p2 + 1);
                        }
                        if (initCount[0] < 0) initCount[0] = cnt;
                        Log.d("PiBridge", "watcher[" + polls[0] + "] busy=" + busy + " cnt=" + cnt + " body=" + body);
                        if (busy) busySeen[0] = true;
                        // 用户原话回显/空文本 → 跳过
                        if (body.isEmpty() || body.equals(sentText) || body.contains(sentText)) {
                            stable[0] = 0; last[0] = t;
                            chatWeb.postDelayed(this, 1000); return;
                        }
                        if (busy) { // 还在流式，继续等
                            stable[0] = 0; last[0] = t;
                            chatWeb.postDelayed(this, 1000); return;
                        }
                        // 不在流式：必须是「见过流式」或「消息数变多」才是新答案
                        if (!busySeen[0] && !(cnt > initCount[0] && initCount[0] >= 0)) {
                            last[0] = t; stable[0] = 0;
                            chatWeb.postDelayed(this, 1000); return;
                        }
                        if (t.equals(last[0])) stable[0]++; else stable[0] = 0;
                        last[0] = t;
                        if (stable[0] >= 1 && body.length() > 2) { // 1 秒不变+非流式=完成
                            String say = body.length() > 220 ? body.substring(0, 220) + "……" : body;
                            Log.d("PiBridge", "TTS触发: " + say);
                            voiceMsg("🔊 朗读中…");
                            final String fSay = say;
                            new Thread(() -> {
                                try {
                                    org.json.JSONObject env = Tools.call("tts_speak", new JSONObject().put("text", fSay));
                                    Log.d("PiBridge", "tts_speak 结果: " + env);
                                } catch (Exception e) { Log.e("PiBridge", "tts_speak 异常", e); }
                            }, "tts-report").start();
                            voiceStrip.postDelayed(() -> voiceStrip.setVisibility(View.GONE), 8000);
                            return;
                        }
                        chatWeb.postDelayed(this, 1000);
                    });
            }
        }, 2000);
    }

    @Override protected void onResume() {
        super.onResume();
        if (PENDING_CONVO) { PENDING_CONVO = false; startConvo(); }
        if (PENDING_TASK != null) { String q = PENDING_TASK; PENDING_TASK = null; switchTab(0); injectPrompt(q, true); }
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
