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
                    int n = ar.read(chunk, 0, chunk.length);
                    if (n <= 0) { Thread.sleep(50); continue; }
                    for (int i = 0; i < n; i++) { ring[wpos] = chunk[i]; wpos = (wpos + 1) % ringN; }
                    total += n;
                    if (total - lastBeat > sr * 2) { writeState(this, true); lastBeat = total; }
                    double sumSq = 0;
                    for (int i = 0; i < n; i++) { double sv = chunk[i]; sumSq += sv * sv; }
                    double rms = Math.sqrt(sumSq / n);
                    if (Tools.ttsSpeaking || VoiceCore.running || Tools.micBusy) { state = 0; continue; }
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
                        boolean endOfSpeech = silenceMs >= 800 && uttLen >= sr * 800 / 1000;
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
            try {
                JSONObject env = Tools.call("stt_transcribe", new JSONObject().put("file", chunkWav.getAbsolutePath()));
                if (env != null && env.optBoolean("ok")) txt = env.optString("data", "");
            } catch (Exception ignore) {}
            txt = txt.replaceAll("<\\|[^>]*\\|>", "").replace(" ", "").trim();
            Log.d("PiBridge", "唤醒转写: " + txt);
            if (txt.isEmpty() || txt.startsWith("(")) return;
            String norm = txt.replace("秋", "丘").replace("邱", "丘").replace("九", "丘");
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


    /** 唤醒后：回应→采集指令→分流执行→回到监听 */
    private void wakeRound(String heardOriginal) {
        try {
            String said = heardOriginal == null ? "" : heardOriginal.replaceAll("[，。！？,.!?、\\s]+", "");
            String norm = said.replace("秋", "丘").replace("邱", "丘");
            String[] wakes = {"小丘小丘", "你好小丘", "嘿小丘", "嗨小丘", "小丘"};
            String carry = "";
            for (String w0 : wakes) {
                if (norm.startsWith(w0)) { carry = said.substring(w0.length()); break; }
            }
            // 唤醒后总是续听（处理分块切开的长指令）：beep → 续录 → 拼接 → 执行
            try { // 提示音：50ms 即响，比云TTS快3秒
                android.media.ToneGenerator tg = new android.media.ToneGenerator(android.media.AudioManager.STREAM_MUSIC, 80);
                tg.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 120);
                Thread.sleep(150);
            } catch (Exception ignore) {}
            File wav = WavUtil.recordAutoStop(this, 15);
            if (wav == null) return;
            String heard = "";
            try {
                JSONObject env = Tools.call("stt_transcribe", new org.json.JSONObject().put("file", wav.getAbsolutePath()));
                if (env != null && env.optBoolean("ok")) heard = env.optString("data", "").trim();
            } catch (Exception ignore) {}
            if (heard.isEmpty()) heard = carry;
            else if (!carry.isEmpty()) heard = carry + heard; // 分块切开的指令拼接
            if (heard.isEmpty()) return;
            Log.i("PiBridge", "指令: " + heard);
            execCommand(heard);
        } catch (Exception e) { Log.w("PiBridge", "wakeRound: " + e); }
    }

    private void execCommand(String heard) {
        try {
            if (heard == null || heard.isEmpty()) return;
            if (heard.matches(".*(结束对话|停止聆听).*")) return;
            JSONObject fast = Tools.call("chat_fast", new org.json.JSONObject().put("q", heard));
            String answer = null;
            if (fast != null && fast.optBoolean("ok")) {
                JSONObject d = fast.optJSONObject("data");
                if (d != null && "chat".equals(d.optString("type"))) answer = d.optString("answer", "");
            }
            if (answer != null && !answer.isEmpty()) {
                Tools.call("tts_speak", new org.json.JSONObject().put("text", answer));
                return;
            }
            MainActivity.PENDING_TASK = heard;
            Intent i = new Intent(this, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) { Log.w("PiBridge", "execCommand: " + e); }
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
