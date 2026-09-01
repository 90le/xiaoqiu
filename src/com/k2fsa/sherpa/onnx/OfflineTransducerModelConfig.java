package com.k2fsa.sherpa.onnx;

public class OfflineTransducerModelConfig {
    public String encoder = "";
    public String decoder = "";
    public String joiner = "";
    public QnnConfig qnnConfig = new QnnConfig();
    public OfflineTransducerModelConfig() {}
    public OfflineTransducerModelConfig(String encoder, String decoder, String joiner, QnnConfig qnnConfig) {
        this.encoder = encoder;
        this.decoder = decoder;
        this.joiner = joiner;
        this.qnnConfig = qnnConfig;
    }
}
