package com.k2fsa.sherpa.onnx;

public class OfflineWhisperModelConfig {
    public String encoder = "";
    public String decoder = "";
    public String language = "en";
    public String task = "transcribe";
    public int tailPaddings = 1000;
    public boolean enableTokenTimestamps = false;
    public boolean enableSegmentTimestamps = false;

    public OfflineWhisperModelConfig() {}
    public OfflineWhisperModelConfig(String encoder, String decoder, String language, String task,
            int tailPaddings, boolean enableTokenTimestamps, boolean enableSegmentTimestamps) {
        this.encoder = encoder; this.decoder = decoder; this.language = language; this.task = task;
        this.tailPaddings = tailPaddings; this.enableTokenTimestamps = enableTokenTimestamps;
        this.enableSegmentTimestamps = enableSegmentTimestamps;
    }
}
