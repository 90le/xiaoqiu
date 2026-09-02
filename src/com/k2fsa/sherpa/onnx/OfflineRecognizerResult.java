package com.k2fsa.sherpa.onnx;

public class OfflineRecognizerResult {
    public String text = "";
    public String[] tokens = new String[0];
    public float[] timestamps = new float[0];
    public String lang = "";
    public String emotion = "";
    public String event = "";
    public float[] durations = new float[0];

    public OfflineRecognizerResult(String text, String[] tokens, float[] timestamps,
            String lang, String emotion, String event, float[] durations) {
        this.text = text; this.tokens = tokens; this.timestamps = timestamps;
        this.lang = lang; this.emotion = emotion; this.event = event; this.durations = durations;
    }

    public String getText() { return text; }
}
