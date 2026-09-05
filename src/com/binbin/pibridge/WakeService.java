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
            // 短句（<3.5s，多为唤醒词）：云端 GLM-ASR 优先——更快更准（"小丘"不再转成"小舅"）
            if (seg.length < sr * 35 / 10) {
                try { txt = Tools.cloudStt(chunkWav); } catch (Exception ignore) { txt = null; }
                if (txt == null) txt = "";
                Log.d("PiBridge", "唤醒转写(云): " + txt);
            }
            if (txt.isEmpty()) {
                try {
                    JSONObject env = Tools.call("stt_transcribe", new JSONObject().put("file", chunkWav.getAbsolutePath()));
                    if (env != null && env.optBoolean("ok")) txt = env.optString("data", "");
                } catch (Exception ignore) {}
            }
            txt = txt.replaceAll("<\\|[^>]*\\|>", "").replace(" ", "").trim();
            Log.d("PiBridge", "唤醒转写: " + txt);
            if (txt.isEmpty() || txt.startsWith("(")) return;
            String norm = wakeNorm(txt);
            boolean hit = false;
            for (String w2 : new String[]{"小丘", "小丘丘", "你好小丘", "嘿小丘", "嗨小丘", "丘丘"}) {
                if (norm.contains(w2)) { hit = true; break; }
            }
            if (!hit) return;
            Log.i("PiBridge", "🔔 唤醒命中: " + txt);
            sendBroadcast(new android.content.Intent("com.pihost.WAKE_ANIM"));
            if (ar != null) { ar.stop(); ar.release(); ar = null; }
            wakeRound(norm);
            Thread.sleep(400);
        } catch (Exception e) { Log.w("PiBridge", "utt: " + e); }
    }


    /** 唤醒词同音归一化：只影响命中匹配与剥离，不改指令内容（实测误转样本：小舅） */
    private static String wakeNorm(String s) {
        return s.replace("秋", "丘").replace("邱", "丘").replace("舅", "丘").replace("九", "丘").replace("球", "丘");
    }

    private static final String[] WAKE_REPLIES = {"在！", "我在！", "诶！", "嗯！"};
    private static final String[] BYE_TIMEOUT = {"嗯，我先退下", "我先歇着啦"};
    private static final String[] BYE_BYE = {"好嘞", "嗯呐"};

    /** 唤醒后：秒回应（本地TTS+全屏流光）→ 连续对话循环（VAD断句→识别→执行→播报）
     *  收尾：用户说"结束/说完了/退下" or 一轮3秒没人说话（用户指定规则） */
    private void wakeRound(String heardOriginal) {
        sendBroadcast(new android.content.Intent("com.pihost.WAKE_GLOW_ON"));
        try {
            // ① 秒回应：本地 TTS 零网络延迟
            String said = heardOriginal == null ? "" : heardOriginal.replaceAll("[，。！？,.!?、\\s]+", "");
            String norm = wakeNorm(said);
            String[] wakes = {"小丘小丘", "你好小丘", "嘿小丘", "嗨小丘", "小丘"};
            String carry = "";
            for (String w0 : wakes) {
                if (norm.startsWith(w0)) { carry = said.substring(w0.length()); break; }
            }
            Tools.speakLocal(WAKE_REPLIES[new java.util.Random().nextInt(WAKE_REPLIES.length)]);
            waitSpeak(1600);

            // ② 连续对话循环
            int noiseRounds = 0;
            while (running) {
                String heard = carry; carry = "";
                if (heard.isEmpty()) {
                    File wav = WavUtil.recordAutoStop(this, 12, 6000); // 6秒无人声→null→收尾（用户定：5-8秒）
                    if (wav == null) {
                        Tools.speakLocal(BYE_TIMEOUT[new java.util.Random().nextInt(BYE_TIMEOUT.length)]);
                        break;
                    }
                    heard = transcribe(wav);
                    if (heard == null || heard.isEmpty()) { // 噪声/无文本：重听（仍受3秒规则约束）
                        if (++noiseRounds >= 3) { Tools.speakLocal("没听清，需要我做什么直接说"); noiseRounds = 0; }
                        continue;
                    }
                }
                noiseRounds = 0;
                if (heard.matches(".*(结束对话|结束|说完了|退下|没事了|不用了|再见).*")) {
                    Tools.speakLocal(BYE_BYE[new java.util.Random().nextInt(BYE_BYE.length)]);
                    break;
                }
                boolean opened = execCommand(heard);
                if (opened) break; // 复杂任务转交屏幕会话结束
                boolean cut = waitSpeak(25000); // 等播报完；被打断立即续听
                if (cut) continue; // 打断：跳过3秒等待直接采下一条（0.6s预滚已接住话头）
            }
        } catch (Exception e) {
            Log.w("PiBridge", "wakeRound: " + e);
        } finally {
            sendBroadcast(new android.content.Intent("com.pihost.WAKE_GLOW_OFF"));
        }
    }

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

    /** 等播报结束；期间监测用户开口→打断（停播返回 true）。 */
    private boolean waitSpeak(long timeoutMs) {
        long t0 = System.currentTimeMillis();
        boolean gaveHead = false; // 回应开头 600ms 不算（防 TTS 扬声器回声）
        int hot = 0;
        android.media.AudioRecord bar = null;
        short[] buf = new short[1600]; // 100ms
        try {
            int minBuf = android.media.AudioRecord.getMinBufferSize(16000, android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT);
            bar = new android.media.AudioRecord(android.media.MediaRecorder.AudioSource.MIC, 16000,
                    android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT, Math.max(minBuf, 16000 * 2));
            bar.startRecording();
        } catch (Exception e) { bar = null; }
        try {
            while (Tools.ttsSpeaking && System.currentTimeMillis() - t0 < timeoutMs) {
                Thread.sleep(80);
                long el = System.currentTimeMillis() - t0;
                if (!gaveHead && el > 600) gaveHead = true;
                if (bar != null && gaveHead) {
                    int n = bar.read(buf, 0, buf.length);
                    if (n > 0) {
                        double sumSq = 0;
                        for (int i = 0; i < n; i++) { double sv = buf[i]; sumSq += sv * sv; }
                        double rms = Math.sqrt(sumSq / n);
                        if (rms > 3200) { if (++hot >= 4) { Tools.stopTts(); return true; } } // 400ms 大声=打断
                        else hot = 0;
                    }
                }
            }
        } catch (Exception ignore) {} finally {
            try { if (bar != null) { bar.stop(); bar.release(); } } catch (Exception ignore) {}
        }
        return false;
    }

    /** 返回 true=已转交 App 屏幕（会话结束） */
    private boolean execCommand(String heard) {
        try {
            if (heard == null || heard.isEmpty()) return false;
            if (heard.matches(".*(结束对话|停止聆听).*")) return false;
            JSONObject fast = Tools.call("chat_fast", new org.json.JSONObject().put("q", heard));
            String answer = null;
            if (fast != null && fast.optBoolean("ok")) {
                JSONObject d = fast.optJSONObject("data");
                if (d != null && "chat".equals(d.optString("type"))) answer = d.optString("answer", "");
            }
            if (answer != null && !answer.isEmpty()) {
                Tools.call("tts_speak", new org.json.JSONObject().put("text", answer));
                return false; // 快答：语音会话继续
            }
            // 复杂任务：立即广播注入 App 当前对话（不弹屏），本地秒答确认——
            // 确认由唤醒进程自己说（不依赖 App 回执，杜绝"静默几秒啥也没有"）
            android.content.Intent bi = new android.content.Intent("com.pihost.WAKE_TASK");
            bi.putExtra("q", heard);
            sendBroadcast(bi);
            Log.i("PiBridge", "🔔 任务已广播: " + heard);
            Tools.speakLocal("好嘞，这就办");
            waitSpeak(1500);
            return true;
        } catch (Exception e) { Log.w("PiBridge", "execCommand: " + e); return false; }
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
