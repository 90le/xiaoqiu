package com.k2fsa.sherpa.onnx;

public class OfflineFunAsrNanoModelConfig {
    public String encoderAdaptor = "";
    public String llm = "";
    public String embedding = "";
    public String tokenizer = "";
    public String systemPrompt = "You are a helpful assistant.";
    public String userPrompt = "语音转写：";
    public int maxNewTokens = 512;
    public float temperature = 1e-6f;
    public float topP = 0.8f;
    public int seed = 42;
    public String language = "";
    public boolean itn = true;
    public String hotwords = "";

    public OfflineFunAsrNanoModelConfig() {}
    public OfflineFunAsrNanoModelConfig(String encoderAdaptor, String llm, String embedding, String tokenizer,
            String systemPrompt, String userPrompt, int maxNewTokens, float temperature, float topP,
            int seed, String language, boolean itn, String hotwords) {
        this.encoderAdaptor = encoderAdaptor; this.llm = llm; this.embedding = embedding;
        this.tokenizer = tokenizer; this.systemPrompt = systemPrompt; this.userPrompt = userPrompt;
        this.maxNewTokens = maxNewTokens; this.temperature = temperature; this.topP = topP;
        this.seed = seed; this.language = language; this.itn = itn; this.hotwords = hotwords;
    }
}
