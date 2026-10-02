package com.modelgate.openai;

/** 客户端请求了网关尚未支持的能力。 */
public class UnsupportedFeatureException extends RuntimeException {
    public UnsupportedFeatureException(String message) {
        super(message);
    }
}
