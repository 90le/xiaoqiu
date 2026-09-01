package com.k2fsa.sherpa.onnx;

public class OfflineRecognizerResult {
    public String text = "";
    public String[] tokens = new String[0];
    public float[] timestamps = new float[0];
    public String lang = "";
    public String emotion = "";
    public String event = "";
    public float[] durations = new float[0];

    public String getText() { return text; }
}
