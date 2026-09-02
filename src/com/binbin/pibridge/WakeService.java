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
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "xiaoqiu:wake");
        wl.acquire();
        try {
            File dir = new File(getFilesDir(), "sherpa/kws/sherpa-onnx-kws-zipformer-wenetspeech-3.3M-2024-01-01");
            while (running) {
                try {
                    if (ar == null) {
                        int minBuf = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                        ar = new AudioRecord(MediaRecorder.AudioSource.MIC, 16000,
                                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(minBuf, 16000 * 2 * 4));
                        ar.startRecording();
                        Log.i("PiBridge", "唤醒监听就绪（SenseVoice 分块模式）");
                    }
                    // 分块采集 2.8 秒 → 本地识别 → 检测唤醒词
                    int total = (int) (16000 * 1.6);
                    short[] buf = new short[total];
                    int got = 0;
                    while (got < total && running && !VoiceCore.running && !Tools.micBusy) {
                        int n = ar.read(buf, got, total - got);
                        if (n <= 0) break;
                        got += n;
                    }
                    writeState(this, true); // 心跳
                    if (!running || got < total) { Thread.sleep(200); continue; }
                    byte[] pcm = new byte[got * 2];
                    for (int i = 0; i < got; i++) { pcm[i*2] = (byte)(buf[i] & 255); pcm[i*2+1] = (byte)((buf[i] >> 8) & 255); }
                    File chunkWav = new File(getCacheDir(), "wake-chunk.wav");
                    com.binbin.pibridge.WavUtil.writeWav(chunkWav, pcm, 16000, 1, 16);
                    String txt = "";
                    try {
                        JSONObject env = Tools.call("stt_transcribe", new JSONObject().put("file", chunkWav.getAbsolutePath()));
                        if (env != null && env.optBoolean("ok")) txt = env.optString("data", "");
                    } catch (Exception ignore) {}
                    chunkWav.delete();
                    if (txt.contains("小丘")) {
                        Log.i("PiBridge", "🔔 唤醒命中: " + txt);
                        if (ar != null) { ar.stop(); ar.release(); ar = null; }
                        // 若唤醒词后带了指令，一并处理；否则只回应
                        wakeRound(txt);
                        Thread.sleep(400);
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

    /** 唤醒后：回应→采集指令→分流执行→回到监听 */
    private void wakeRound(String heard0) {
        try {
            String said = heard0 == null ? "" : heard0.replaceAll("[，。！？,.!?、\\s]+", "");
            String[] wakes = {"小丘小丘", "你好小丘", "嘿小丘", "嗨小丘", "小丘"};
            String carry = "";
            for (String w0 : wakes) if (said.startsWith(w0)) { carry = said.substring(w0.length()); break; }
            if (!carry.isEmpty()) {
                Log.i("PiBridge", "唤醒携带指令: " + carry);
                execCommand(carry);
                return;
            }
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
