package com.k2fsa.sherpa.onnx;

public class OfflineCanaryModelConfig {
    public String encoder = "";
    public String decoder = "";
    public String srcLang = "en";
    public String tgtLang = "en";
    public boolean usePnc = true;
    public OfflineCanaryModelConfig() {}
    public OfflineCanaryModelConfig(String encoder, String decoder, String srcLang, String tgtLang, boolean usePnc) {
        this.encoder = encoder;
        this.decoder = decoder;
        this.srcLang = srcLang;
        this.tgtLang = tgtLang;
        this.usePnc = usePnc;
    }
}
