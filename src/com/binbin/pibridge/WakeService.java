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

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        Log.i("PiBridge", "WakeService onCreate");
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(CH, "语音唤醒", NotificationManager.IMPORTANCE_LOW));
        Notification n = new Notification.Builder(this, CH)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("小丘待命中")
                .setContentText("说「小丘」唤醒 · 双击悬浮球也可")
                .setOngoing(true).build();
        startForeground(2001, n);
        running = true;
        new Thread(this::loop, "wake-kws").start();
    }

    private void loop() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "xiaoqiu:wake");
        wl.acquire();
        try {
            File dir = new File(getFilesDir(), "sherpa/kws/sherpa-onnx-kws-zipformer-wenetspeech-3.3M-2024-01-01");
            File kwFile = new File(dir, "keywords.txt");
            if (!dir.isDirectory() || !kwFile.isFile()) { Log.w("PiBridge", "唤醒模型未就绪"); running = false; return; }
            Log.i("PiBridge", "KWS: 模型目录OK，开始初始化");
            if (Tools.initKwsOnce(dir)) {
                Log.i("PiBridge", "全局唤醒已启动，等待「小丘」");
            } else { running = false; return; }
            AudioRecord ar = null;
            while (running) {
                try {
                    // mic 让位：连续对话/按住说话进行中则暂停 KWS
                    if (VoiceCore.running || Tools.micBusy) { if (ar != null) { ar.stop(); ar.release(); ar = null; } Thread.sleep(250); continue; }
                    if (ar == null) {
                        int minBuf = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                        ar = new AudioRecord(MediaRecorder.AudioSource.MIC, 16000,
                                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(minBuf, 16000 * 2 * 4));
                        ar.startRecording();
                        OnlineStream stream = Tools.kwsCreateStream();
                        if (stream == null) break;
                        Tools.kwsSetStream(stream);
                        Log.i("PiBridge", "KWS 流就绪");
                    }
                    short[] chunk = new short[1600]; // 100ms
                    int n = ar.read(chunk, 0, chunk.length);
                    if (n <= 0) continue;
                    float[] f = new float[n];
                    for (int i = 0; i < n; i++) f[i] = chunk[i] / 32768f;
                    String kw = Tools.kwsFeed(f);
                    if (kw != null && !kw.isEmpty() && !kw.equals(lastKeyword + "|")) {
                        lastKeyword = kw;
                        Log.i("PiBridge", "🔔 唤醒词命中: " + kw);
                        // 让出麦克风做命令采集
                        if (ar != null) { ar.stop(); ar.release(); ar = null; }
                        Tools.kwsClearStream();
                        wakeRound(kw);
                    }
                } catch (Exception e) { Log.w("PiBridge", "wake loop: " + e); Thread.sleep(800); }
            }
            if (ar != null) { try { ar.stop(); ar.release(); } catch (Exception ignore) {} }
        } catch (Exception e) {
            Log.w("PiBridge", "wake fatal: " + e);
        } finally {
            running = false;
            wl.release();
        }
    }

    /** 唤醒后：回应→采集指令→分流执行→回到监听 */
    private void wakeRound(String kw) {
        try {
            Tools.call("tts_speak", new org.json.JSONObject().put("text", "在"));
            Thread.sleep(600);
            File wav = WavUtil.recordAutoStop(this, 15);
            if (wav == null) return; // 没说指令，回监听
            String heard = "";
            try {
                JSONObject env = Tools.call("stt_transcribe", new org.json.JSONObject().put("file", wav.getAbsolutePath()));
                if (env != null && env.optBoolean("ok")) heard = env.optString("data", "").trim();
            } catch (Exception ignore) {}
            if (heard.isEmpty()) return;
            Log.i("PiBridge", "指令: " + heard);
            if (heard.matches(".*(结束对话|停止聆听).*")) return;
            JSONObject fast = Tools.call("chat_fast", new org.json.JSONObject().put("q", heard));
            String answer = null;
            if (fast != null && fast.optBoolean("ok")) {
                JSONObject d = fast.optJSONObject("data");
                if (d != null && "chat".equals(d.optString("type"))) answer = d.optString("answer", "");
            }
            if (answer != null && !answer.isEmpty()) {
                Tools.call("tts_speak", new org.json.JSONObject().put("text", answer));
                return; // 已播报，回监听
            }
            // 任务型 → 交接给 App 内 pi
            MainActivity.PENDING_TASK = heard;
            Intent i = new Intent(this, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) { Log.w("PiBridge", "wakeRound: " + e); }
    }

    @Override public void onDestroy() { running = false; super.onDestroy(); }
}
