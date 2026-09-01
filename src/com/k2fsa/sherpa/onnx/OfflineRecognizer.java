package com.k2fsa.sherpa.onnx;

import android.content.res.AssetManager;

public class OfflineRecognizer {
    private long ptr;

    public OfflineRecognizer(AssetManager assetManager, OfflineRecognizerConfig config) {
        ptr = assetManager != null ? newFromAsset(assetManager, config) : newFromFile(config);
        if (ptr == 0) throw new IllegalArgumentException("Invalid OfflineRecognizerConfig: failed to create native OfflineRecognizer");
    }

    public void release() { if (ptr != 0) { delete(ptr); ptr = 0; } }
    public OfflineStream createStream() { return new OfflineStream(createStream(ptr)); }
    public OfflineRecognizerResult getResult(OfflineStream s) { return getResult(s.getPtr()); }
    public void decode(OfflineStream s) { decode(ptr, s.getPtr()); }

    private native long newFromFile(OfflineRecognizerConfig config);
    private native long newFromAsset(AssetManager assetManager, OfflineRecognizerConfig config);
    private native void decode(long ptr, long streamPtr);
    private native OfflineRecognizerResult getResult(long streamPtr);
    private native void delete(long ptr);
    private native long createStream(long ptr);

    static { System.loadLibrary("sherpa-onnx-jni"); }
}
