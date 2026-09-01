package com.k2fsa.sherpa.onnx;

public class OfflineQwen3AsrModelConfig {
    public String convFrontend = "";
    public String encoder = "";
    public String decoder = "";
    public String tokenizer = "";
    public int maxTotalLen = 512;
    public int maxNewTokens = 128;
    public float temperature = 1e-6f;
    public float topP = 0.8f;
    public int seed = 42;
    public String hotwords = "";

    public OfflineQwen3AsrModelConfig() {}
    public OfflineQwen3AsrModelConfig(String convFrontend, String encoder, String decoder, String tokenizer,
            int maxTotalLen, int maxNewTokens, float temperature, float topP, int seed, String hotwords) {
        this.convFrontend = convFrontend; this.encoder = encoder; this.decoder = decoder;
        this.tokenizer = tokenizer; this.maxTotalLen = maxTotalLen; this.maxNewTokens = maxNewTokens;
        this.temperature = temperature; this.topP = topP; this.seed = seed; this.hotwords = hotwords;
    }
}
