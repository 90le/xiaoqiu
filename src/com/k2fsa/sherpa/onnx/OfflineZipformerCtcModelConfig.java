package com.k2fsa.sherpa.onnx;

public class OfflineZipformerCtcModelConfig {
    public String model = "";
    public QnnConfig qnnConfig = new QnnConfig();
    public OfflineZipformerCtcModelConfig() {}
    public OfflineZipformerCtcModelConfig(String model, QnnConfig qnnConfig) {
        this.model = model;
        this.qnnConfig = qnnConfig;
    }
}
