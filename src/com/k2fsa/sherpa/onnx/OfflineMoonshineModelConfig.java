package com.k2fsa.sherpa.onnx;

public class OfflineMoonshineModelConfig {
    public String preprocessor = "";
    public String encoder = "";
    public String uncachedDecoder = "";
    public String cachedDecoder = "";
    public String mergedDecoder = "";
    public OfflineMoonshineModelConfig() {}
    public OfflineMoonshineModelConfig(String preprocessor, String encoder, String uncachedDecoder, String cachedDecoder, String mergedDecoder) {
        this.preprocessor = preprocessor;
        this.encoder = encoder;
        this.uncachedDecoder = uncachedDecoder;
        this.cachedDecoder = cachedDecoder;
        this.mergedDecoder = mergedDecoder;
    }
}
