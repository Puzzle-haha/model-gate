package com.modelgate.provider;

/** 请求了一个没有任何供应商能提供的模型。 */
public class UnknownModelException extends RuntimeException {

    private final String model;

    public UnknownModelException(String model, java.util.List<String> known) {
        super("未知模型: " + model + "。当前可用: " + String.join(", ", known));
        this.model = model;
    }

    public String model() {
        return model;
    }
}
