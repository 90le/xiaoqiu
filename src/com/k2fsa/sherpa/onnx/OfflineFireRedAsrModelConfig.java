package com.k2fsa.sherpa.onnx;

public class OfflineFireRedAsrModelConfig {
    public String encoder = "";
    public String decoder = "";
    public OfflineFireRedAsrModelConfig() {}
    public OfflineFireRedAsrModelConfig(String encoder, String decoder) {
        this.encoder = encoder;
        this.decoder = decoder;
    }
}
