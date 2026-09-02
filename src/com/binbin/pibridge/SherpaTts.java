package com.binbin.pibridge;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.util.Log;
import com.k2fsa.sherpa.onnx.OfflineTts;
import com.k2fsa.sherpa.onnx.OfflineTtsConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig;
import java.io.File;

/** sherpa-onnx 神经 TTS（melo 中文）：自然度远超系统引擎 */
public class SherpaTts {
    /** 总闸：神经TTS 与当前 .so 存在兼容性问题，默认关闭；待版本匹配后再启用 */
    public static volatile boolean enabled = false;
    private static volatile OfflineTts tts;
    private static volatile boolean ready = false;
    private static AudioTrack track;

    public static boolean init(Context ctx) {
        if (ready) return true;
        synchronized (SherpaTts.class) {
            if (ready) return true;
            try {
                File dir = new File(ctx.getFilesDir(), "sherpa/tts/vits-melo-tts-zh_en");
                if (!dir.isDirectory()) { Log.i("PiBridge", "神经TTS: 模型未就绪 " + dir); return false; }
                enabled = new File(dir.getParentFile(), "NEURAL_ON").isFile();
                if (!enabled) return false;
                Log.i("PiBridge", "神经TTS: 开始初始化…");
                Log.i("PiBridge", "神经TTS: 模型目录 OK，开始构造引擎…");
                File modelF = new File(dir, "model.onnx");
                if (!modelF.isFile()) modelF = new File(dir, "model.int8.onnx");
                String ruleFsts = new File(dir, "date.fst").getAbsolutePath()
                        + "," + new File(dir, "number.fst").getAbsolutePath()
                        + "," + new File(dir, "phone.fst").getAbsolutePath()
                        + "," + new File(dir, "new_heteronym.fst").getAbsolutePath();
                OfflineTtsVitsModelConfig vits = new OfflineTtsVitsModelConfig(
                        modelF.getAbsolutePath(),
                        new File(dir, "lexicon.txt").getAbsolutePath(),
                        new File(dir, "tokens.txt").getAbsolutePath(),
                        "",
                        new File(dir, "dict").getAbsolutePath(),
                        1.0f, 1.0f, 1.0f);
                OfflineTtsModelConfig mc = new OfflineTtsModelConfig(vits,
                        new com.k2fsa.sherpa.onnx.OfflineTtsMatchaModelConfig(),
                        new com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig(),
                        new com.k2fsa.sherpa.onnx.OfflineTtsZipVoiceModelConfig(),
                        new com.k2fsa.sherpa.onnx.OfflineTtsKittenModelConfig(),
                        new com.k2fsa.sherpa.onnx.OfflineTtsPocketModelConfig(),
                        new com.k2fsa.sherpa.onnx.OfflineTtsSupertonicModelConfig(),
                        2, false, "cpu");
                OfflineTtsConfig cfg = new OfflineTtsConfig(mc, ruleFsts, "", 1, 0f);
                Log.i("PiBridge", "神经TTS: 引擎构造中（加载 int8 模型+词典，可能数十秒）…");
                tts = new OfflineTts(ctx.getAssets(), cfg);
                Log.i("PiBridge", "神经TTS: 构造完成");
                ready = true;
                Log.i("PiBridge", "神经TTS 就绪, 采样率=" + tts.sampleRate() + ", 说话人=" + tts.numSpeakers());
                return true;
            } catch (Throwable e) {
                Log.w("PiBridge", "神经TTS 初始化失败: " + e);
                ready = false;
                return false;
            }
        }
    }

    public static boolean isReady() { return ready; }

    /** 合成并阻塞播放（调用方放后台线程），返回时长毫秒 */
    public static long speak(String text, float speed) {
        if (!ready || tts == null) return -1;
        try {
            stop();
            com.k2fsa.sherpa.onnx.GeneratedAudio audio = tts.generate(text, 0, speed);
            int sr = audio.getSampleRate() > 0 ? audio.getSampleRate() : tts.sampleRate();
            float[] fs = audio.getSamples();
            int bufSize = fs.length * 2;
            track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sr).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(bufSize)
                    .build();
            short[] pcm = new short[fs.length];
            for (int i = 0; i < pcm.length; i++) {
                float s = fs[i];
                pcm[i] = (short) Math.max(-32768, Math.min(32767, s * 32767f));
            }
            track.write(pcm, 0, pcm.length);
            track.play();
            return bufSize * 1000L / 2 / sr;
        } catch (Throwable e) {
            Log.w("PiBridge", "神经TTS 播放失败: " + e);
            return -1;
        }
    }

    public static void stop() {
        try { if (track != null) { track.stop(); track.release(); track = null; } } catch (Exception ignore) {}
    }
}
