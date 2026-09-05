package com.binbin.pibridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;
import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.k2fsa.sherpa.onnx.KeywordSpotter;
import com.k2fsa.sherpa.onnx.KeywordSpotterResult;
import com.k2fsa.sherpa.onnx.OnlineModelConfig;
import com.k2fsa.sherpa.onnx.OnlineStream;
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig;
import org.json.JSONObject;
import com.k2fsa.sherpa.onnx.QnnConfig;
import java.io.File;

/** 全局唤醒词服务：息屏/任意界面喊「小丘」即可唤醒执行（sherpa KWS 离线） */
public class WakeService extends Service {
    private static final String CH = "wake";
    private AudioRecord ar; // 常驻字段：服务任何退出路径都能立即释放麦克风
    private static volatile boolean running = false;
    private static KeywordSpotter kws;
    private static String lastKeyword = "";

    public static boolean isRunning() { return running; }

    public static void start(Context c) {
        if (running) return;
        c.startService(new Intent(c, WakeService.class));
    }
    public static void stop(Context c) {
        running = false;
        c.stopService(new Intent(c, WakeService.class));
        MAIN.post(() -> { try { if (kws != null) { kws.release(); kws = null; } } catch (Exception ignore) {} });
    }
    private static final android.os.Handler MAIN = new android.os.Handler(Looper.getMainLooper());

    /** 唤醒状态落文件：:kws 进程写，主进程读（跨进程唯一可信来源） */
    static void writeState(Context c, boolean on) {
        try {
            Tools.write(new File(c.getFilesDir(), "wake-state.json"),
                    new org.json.JSONObject().put("running", on).put("ts", System.currentTimeMillis()).toString());
        } catch (Exception ignore) {}
    }
    static boolean readState(Context c) {
        try {
            org.json.JSONObject o = new org.json.JSONObject(
                    new String(java.nio.file.Files.readAllBytes(new File(c.getFilesDir(), "wake-state.json").toPath()), "UTF-8"));
            if (!o.optBoolean("running")) return false;
            return System.currentTimeMillis() - o.optLong("ts") < 20000; // 心跳超20秒视为已死
        } catch (Exception e) { return false; }
    }

    @Override public IBinder onBind(Intent i) { return null; }

    private volatile boolean sessionActive = false; // 会话循环占用中（主监听让位）
    @Override public void onCreate() {
        // 前台服务：息屏不被 MIUI 冻结/回收（常驻通知=唤醒待命中的存在感）
        try {
            android.app.NotificationManager nm = (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            android.app.NotificationChannel ch = new android.app.NotificationChannel("wake", "语音唤醒", android.app.NotificationManager.IMPORTANCE_MIN);
            ch.setShowBadge(false); nm.createNotificationChannel(ch);
            android.app.Notification n = new android.app.Notification.Builder(this, "wake")
                    .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                    .setContentTitle("小丘待命中")
                    .setContentText("任意界面/息屏喊「小丘」唤醒我")
                    .setOngoing(true)
                    .build();
            startForeground(1001, n);
        } catch (Exception e) { Log.w("PiBridge", "fgs: " + e); }
        super.onCreate();
        Log.i("PiBridge", "WakeService onCreate");
        Tools.init(this); // :kws 独立进程必须自行初始化 Tools（ctx/引擎/配置）
        // 统一会话总线接收（页面引擎 → 本进程）
        registerReceiver(new android.content.BroadcastReceiver() {
            @Override public void onReceive(Context c2, android.content.Intent i) { turnDone = true; }
        }, new android.content.IntentFilter("com.pihost.VOICE_DONE"));
        registerReceiver(new android.content.BroadcastReceiver() {
            @Override public void onReceive(Context c2, android.content.Intent i) {
                String cmd = i.getStringExtra("cmd");
                if ("start".equals(cmd)) {
                    if (!sessionActive) { // 🎙点火：从主监听切进会话循环
                        sessionActive = true;
                        new Thread(() -> { try { if (ar != null) { ar.stop(); ar.release(); ar = null; } } catch (Exception ignore) {} sessionLoop(i.getStringExtra("from") == null ? "mic" : i.getStringExtra("from"), ""); }, "mic-session").start();
                    }
                } else { sessionStop = true; }
            }
        }, new android.content.IntentFilter("com.pihost.SESSION_CMD"));
        registerReceiver(new android.content.BroadcastReceiver() {
            @Override public void onReceive(Context c2, android.content.Intent i) {
                pendingSpeak = new String[]{ i.getStringExtra("text"), i.getStringExtra("token") };
            }
        }, new android.content.IntentFilter("com.pihost.VOICE_SPEAK"));
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(CH, "语音唤醒", NotificationManager.IMPORTANCE_LOW));
        Notification n = new Notification.Builder(this, CH)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("小丘待命中")
                .setContentText("说「小丘」唤醒 · 双击悬浮球也可")
                .setOngoing(true).build();
        startForeground(2001, n);
        running = true;
        writeState(this, true);
        new Thread(this::loop, "wake-kws").start();
    }

    private void loop() {
        // 小爱同学式持续监听管线：常录音环形缓冲 + 语音端点检测 + 整句转写（语音零丢失）
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "xiaoqiu:wake");
        wl.acquire();
        try {
            int sr = 16000;
            int ringN = sr * 15;
            short[] ring = new short[ringN];
            int wpos = 0;
            long total = 0;
            final int TH_HIGH = 1500, TH_LOW = 600;
            int state = 0; // 0=静音 1=说话中
            long speechStart = 0, silenceMs = 0;
            int speechStartIdx = 0;
            short[] chunk = new short[sr / 10];
            long lastBeat = 0;
            File dir = new File(getFilesDir(), "sherpa/kws/sherpa-onnx-kws-zipformer-wenetspeech-3.3M-2024-01-01");
            boolean announced = false;
            while (running) {
                try {
                    if (ar == null) {
                        int minBuf = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                        ar = new AudioRecord(MediaRecorder.AudioSource.MIC, 16000,
                                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(minBuf, 16000 * 2 * 4));
                        ar.startRecording();
                        if (!announced) { Log.i("PiBridge", "唤醒监听就绪（持续监听管线：环形缓冲+VAD+整句转写）"); announced = true; }
                    }
                    if (Tools.ttsSpeaking || VoiceCore.running || Tools.micBusy) { Thread.sleep(250); continue; }
                    if (sessionActive) { Thread.sleep(200); continue; } // 会话循环占用：主监听让位
                    try { // 媒体互斥：手机在放音乐/视频时，唤醒让位（不抢麦克风不误识别）
                        android.media.AudioManager am = (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
                        if (am != null && am.isMusicActive()) { Thread.sleep(500); continue; }
                    } catch (Exception ignore) {}
                    int n = ar.read(chunk, 0, chunk.length);
                    if (n <= 0) { Thread.sleep(50); continue; }
                    for (int i = 0; i < n; i++) { ring[wpos] = chunk[i]; wpos = (wpos + 1) % ringN; }
                    total += n;
                    if (total - lastBeat > sr * 2) { writeState(this, true); lastBeat = total; }
                    double sumSq = 0;
                    for (int i = 0; i < n; i++) { double sv = chunk[i]; sumSq += sv * sv; }
                    double rms = Math.sqrt(sumSq / n);
                    if (Tools.ttsSpeaking || VoiceCore.running || Tools.micBusy) { state = 0; continue; }
                    try {
                        android.media.AudioManager am2 = (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
                        if (am2 != null && am2.isMusicActive()) { state = 0; continue; }
                    } catch (Exception ignore) {}
                    if (state == 0) {
                        if (rms >= TH_HIGH) {
                            state = 1; silenceMs = 0;
                            speechStart = Math.max(0, total - n - sr * 300 / 1000); // 300ms预滚
                            speechStartIdx = (int)((speechStart % ringN) + ringN) % ringN;
                            Log.d("PiBridge", "语音段开始");
                        }
                    } else {
                        if (rms >= TH_LOW) silenceMs = 0; else silenceMs += 100;
                        long uttLen = total - speechStart;
                        // 短句（多半是唤醒词）500ms 静默即截断——唤醒提速的关键
                        boolean shortUtt = uttLen < sr * 3;
                        boolean endOfSpeech = silenceMs >= (shortUtt ? 500 : 800) && uttLen >= sr * 800 / 1000;
                        boolean tooLong = uttLen >= sr * 10;
                        if (endOfSpeech || tooLong) {
                            int len = (int) Math.min(uttLen, ringN);
                            int sPos = speechStartIdx;
                            short[] seg = new short[len];
                            for (int i = 0; i < len; i++) seg[i] = ring[(sPos + i) % ringN];
                            state = 0;
                            handleUtterance(seg, sr);
                        }
                    }
                } catch (Exception e) { Log.w("PiBridge", "wake loop: " + e); Thread.sleep(800); }
            }
        } catch (Exception e) {
            Log.w("PiBridge", "wake fatal: " + e);
        } finally {
            releaseMic();
            running = false;
            wl.release();
        }
    }

    /** 整句转写 → 唤醒匹配 → 携带指令执行/对话 */
    private void handleUtterance(short[] seg, int sr) {
        try {
            byte[] pcm = new byte[seg.length * 2];
            for (int i = 0; i < seg.length; i++) { pcm[i*2] = (byte)(seg[i] & 255); pcm[i*2+1] = (byte)((seg[i] >> 8) & 255); }
            File chunkWav = new File(getCacheDir(), "wake-utt.wav");
            com.binbin.pibridge.WavUtil.writeWav(chunkWav, pcm, sr, 1, 16);
            String txt = "";
            String localTxt = "";
            boolean fromCloud = false;
            // 短句（<3.5s，多为唤醒词）：云端 GLM-ASR 优先——更快更准
            if (seg.length < sr * 35 / 10) {
                try { txt = Tools.cloudStt(chunkWav); } catch (Exception ignore) { txt = null; }
                if (txt == null) txt = "";
                fromCloud = !txt.isEmpty();
                Log.d("PiBridge", "唤醒转写(云): " + txt);
            }
            if (!fromCloud) {
                try {
                    JSONObject env = Tools.call("stt_transcribe", new JSONObject().put("file", chunkWav.getAbsolutePath()));
                    if (env != null && env.optBoolean("ok")) { txt = env.optString("data", ""); localTxt = txt; }
                } catch (Exception ignore) {}
            }
            txt = txt.replaceAll("<\\|[^>]*\\|>", "").replace(" ", "").trim();
            Log.d("PiBridge", "唤醒转写: " + txt);
            if (txt.isEmpty() || txt.startsWith("(")) return;
            boolean hit = wakeHit(txt);
            if (!hit && !localTxt.isEmpty()) hit = wakeHit(localTxt); // 云端没中→本地转写再判（口音关键兜底）
            if (!hit) return;
            Log.d("PiBridge", "唤醒命中源: " + (fromCloud ? "云" : "本"));
            if (Tools.ttsSpeaking || System.currentTimeMillis() - lastSpokenAt < 1800 || isEcho(txt)) {
                Log.i("PiBridge", "🛡 回声/保护窗拦截: " + txt); // 自己说话的回声不唤醒（根治自循环）
                return;
            }
            Log.i("PiBridge", "🔔 唤醒命中: " + txt);
            sendBroadcast(new android.content.Intent("com.pihost.WAKE_ANIM"));
            if (ar != null) { ar.stop(); ar.release(); ar = null; }
            // 提取唤醒词后跟的首段指令（有则直接作为第一轮，免重录）
            String said = txt.replaceAll("[，。！？,.!?、\\s]+", "");
            String nn = wakeNorm(said);
            String carryCmd = "";
            for (String w0 : new String[]{"小丘小丘", "你好小丘", "嘿小丘", "嗨小丘", "小丘", "丘丘"}) {
                if (nn.startsWith(w0)) {
                    String rest = nn.substring(w0.length());
                    carryCmd = rest.isEmpty() ? "" : rest; // 归一化文本也接受（指令会被快脑再优化）
                    break;
                }
            }
            sessionLoop("wake", carryCmd);
            Thread.sleep(400);
        } catch (Exception e) { Log.w("PiBridge", "utt: " + e); }
    }


    private static boolean wakeHit(String raw) {
        String norm = wakeNorm(raw == null ? "" : raw);
        for (String w2 : new String[]{"小丘", "小丘丘", "你好小丘", "嘿小丘", "嗨小丘", "丘丘"}) {
            if (norm.contains(w2)) return true;
        }
        return false;
    }

    /** 唤醒词同音归一化：只影响命中匹配与剥离，不改指令内容（实测误转样本：小舅） */
    private static String wakeNorm(String s) {
        for (String h : new String[]{"秋", "邱", "舅", "九", "球", "求", "桥", "乔", "巧", "酒", "瞧", "邱", "囚", "丘"})
            s = s.replace(h, "丘");
        return s;
    }

    private static final String[] WAKE_REPLIES = {"在！", "我在！", "诶！", "嗯！"};
    private static final String[] BYE_TIMEOUT = {"嗯，我先退下", "我先歇着啦"};
    private static final String[] BYE_BYE = {"好嘞", "嗯呐"};

    // ── 会话总线状态（:kws 侧，主线程广播接收器写，会话线程轮询读）──
    private volatile boolean turnDone = false;
    private volatile boolean sessionStop = false;
    private volatile String[] pendingSpeak = null; // {text, token}

    // ── 回声免疫（自唤醒根治）：本进程播过什么，麦克风听到相同内容=回声不算唤醒 ──
    private volatile String lastSpokenText = "";
    private volatile long lastSpokenAt = 0;
    private static final String BS = new String(new char[]{92}); // 反斜杠常量（免转义地狱）
    private void markSpoken(String t) { lastSpokenText = t == null ? "" : t; lastSpokenAt = System.currentTimeMillis(); }
    /** 转写文本是否像自己刚说的话（含"小丘"的自播文本回声） */
    private boolean isEcho(String txt) {
        if (lastSpokenText.isEmpty()) return false;
        if (System.currentTimeMillis() - lastSpokenAt > 25000) { lastSpokenText = ""; return false; } // 25s 记忆窗
        String a = txt.replaceAll("[，。！？,.!?、" + BS + "s]+", "");
        String b = lastSpokenText.replaceAll("[，。！？,.!?、" + BS + "s]+", "");
        return a.equals(b) || (a.length() >= 4 && (b.contains(a) || a.contains(b)));
    }

    /** 统一语音会话循环：录音(VAD)→交脑(VOICE_TURN)→等引擎(VOICE_DONE/VOICE_SPEAK)→续听。
     *  意图分流/prompt优化/结论播报全部在页面引擎；本进程只做 耳+嘴+打断。 */
    private void sessionLoop(String from, String carryIn) {
        sessionActive = true;
        sendBroadcast(new android.content.Intent("com.pihost.WAKE_GLOW_ON"));
        try {
            speakMarked(WAKE_REPLIES[new java.util.Random().nextInt(WAKE_REPLIES.length)]);
            waitLocalSpeak(1600);
            String carry = carryIn == null ? "" : carryIn;
            int noiseRounds = 0;
            while (running && !sessionStop) {
                String heard = carry; carry = "";
                if (heard.isEmpty()) {
                    File wav = WavUtil.recordAutoStop(this, 12, 6000); // 6秒无人声→收尾
                    if (wav == null) {
                        speakMarked(BYE_TIMEOUT[new java.util.Random().nextInt(BYE_TIMEOUT.length)]);
                        break;
                    }
                    heard = transcribe(wav);
                    if (heard == null || heard.isEmpty()) {
                        if (++noiseRounds >= 3) { speakMarked("没听清，需要我做什么直接说"); noiseRounds = 0; }
                        continue;
                    }
                }
                noiseRounds = 0;
                if (heard.matches(".*(结束对话|结束|说完了|退下|没事了|不用了|再见).*")) {
                    speakMarked(BYE_BYE[new java.util.Random().nextInt(BYE_BYE.length)]);
                    break;
                }
                Log.i("PiBridge", "🔔 交脑: " + heard);
                // 交页面引擎：VOICE_TURN → 引擎 (chat_fast/播报/执行/结论) → VOICE_DONE
                turnDone = false; pendingSpeak = null;
                android.content.Intent ti = new android.content.Intent("com.pihost.VOICE_TURN");
                ti.putExtra("text", heard).putExtra("from", from);
                sendBroadcast(ti);
                long t0 = System.currentTimeMillis();
                while (!turnDone && !sessionStop && running && System.currentTimeMillis() - t0 < 150000) {
                    String[] sp = pendingSpeak;
                    if (sp != null) { pendingSpeak = null; speakTurn(sp[0], sp[1]); }
                    Thread.sleep(60);
                }
                if (sessionStop || !running) break;
                if (!turnDone) {
                    speakMarked("这单有点久，进应用里看进度吧");
                    break;
                }
            }
        } catch (Exception e) {
            Log.w("PiBridge", "sessionLoop: " + e);
        } finally {
            sessionStop = false; sessionActive = false;
            sendBroadcast(new android.content.Intent("com.pihost.SESSION_END").putExtra("reason", "bye"));
            sendBroadcast(new android.content.Intent("com.pihost.WAKE_GLOW_OFF"));
        }
    }

    /** 引理发来的播报：快缓存秒播/本地TTS顶上，完成后回执 TTS_STATE(off)+token → 引擎解锁。
     *  播报期间并发监听 RMS=打断（停播即解锁，本轮引擎流程自然收尾）。 */
    private void speakTurn(String text, String token) {
        markSpoken(text); // 回声免疫登记
        try {
            File f = fastFileOf(text);
            if (f != null && f.isFile()) { playFastFile(f, token); return; }
        } catch (Exception ignore) {}
        Tools.speakLocal(text);
        waitLocalSpeak(60000);
        sendBroadcast(new android.content.Intent("com.pihost.TTS_STATE").putExtra("on", false).putExtra("token", token));
    }
    private File fastFileOf(String p) {
        try { java.io.File d = getFilesDir(); return d == null ? null : new File(d, "wake-sounds/" + (p.hashCode() & 0x7fffffff) + ".wav"); } catch (Exception e) { return null; }
    }
    private void playFastFile(File f, String token) {
        try {
            android.media.MediaPlayer mp = android.media.MediaPlayer.create(this, android.net.Uri.fromFile(f));
            if (mp == null) { sendBroadcast(new android.content.Intent("com.pihost.TTS_STATE").putExtra("on", false).putExtra("token", token)); return; }
            final String tk = token;
            Tools.ttsSpeaking = true; // 播放期间占位：主监听让位（防回声）——上一版漏置=自唤醒根因之一
            mp.setOnCompletionListener(m -> { m.release(); Tools.ttsSpeaking = false; sendBroadcast(new android.content.Intent("com.pihost.TTS_STATE").putExtra("on", false).putExtra("token", tk)); });
            mp.start();
            long t0 = System.currentTimeMillis();
            while (mp.isPlaying() && System.currentTimeMillis() - t0 < 60000) Thread.sleep(80);
        } catch (Exception e) {
            Tools.ttsSpeaking = false;
            sendBroadcast(new android.content.Intent("com.pihost.TTS_STATE").putExtra("on", false).putExtra("token", token));
        }
    }
    /** 等本地 TTS 播完：先等"开始播"（首绑引擎可慢至秒级——直接等false会瞬间放行=无声跳过），再等播完 */
    private void waitLocalSpeak(long maxMs) {
        long t0 = System.currentTimeMillis();
        while (!Tools.ttsSpeaking && System.currentTimeMillis() - t0 < 2500) { // 起播窗 2.5s
            try { Thread.sleep(60); } catch (Exception ignore) {}
        }
        while (Tools.ttsSpeaking && System.currentTimeMillis() - t0 < maxMs) {
            try { Thread.sleep(80); } catch (Exception ignore) {}
        }
    }

    /** 固定语播报+回声登记 */
    private void speakMarked(String t) { markSpoken(t); Tools.speakFast(t); }
    /** 转写（本地/云端自动） */
    private String transcribe(File wav) {
        try {
            JSONObject env = Tools.call("stt_transcribe", new JSONObject().put("file", wav.getAbsolutePath()));
            if (env != null && env.optBoolean("ok")) {
                return env.optString("data", "").replaceAll("<\\|[^>]*\\|>", "").replace(" ", "").trim();
            }
        } catch (Exception ignore) {}
        return "";
    }

    @Override public void onDestroy() {
        running = false;
        releaseMic();                 // 立刻关闭麦克风（不等录音块读完）
        writeState(this, false);
        super.onDestroy();
    }
    private void releaseMic() {
        try { if (ar != null) { ar.stop(); ar.release(); ar = null; } } catch (Exception ignore) {}
    }
}
