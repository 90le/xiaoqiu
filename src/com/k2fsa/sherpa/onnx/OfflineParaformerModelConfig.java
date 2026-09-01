package com.k2fsa.sherpa.onnx;

public class OfflineParaformerModelConfig {
    public String model = "";
    public QnnConfig qnnConfig = new QnnConfig();
    public OfflineParaformerModelConfig() {}
    public OfflineParaformerModelConfig(String model, QnnConfig qnnConfig) {
        this.model = model;
        this.qnnConfig = qnnConfig;
    }
}
