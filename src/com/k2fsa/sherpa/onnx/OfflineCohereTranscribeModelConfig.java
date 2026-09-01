package com.k2fsa.sherpa.onnx;

public class OfflineCohereTranscribeModelConfig {
    public String encoder = "";
    public String decoder = "";
    public String language = "";
    public boolean usePunct = true;
    public boolean useItn = true;
    public OfflineCohereTranscribeModelConfig() {}
    public OfflineCohereTranscribeModelConfig(String encoder, String decoder, String language, boolean usePunct, boolean useItn) {
        this.encoder = encoder;
        this.decoder = decoder;
        this.language = language;
        this.usePunct = usePunct;
        this.useItn = useItn;
    }
}
