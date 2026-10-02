package com.modelgate.openai;

/** 请求缺少 API key，但配置要求必须提供。 */
public class MissingApiKeyException extends RuntimeException {
    public MissingApiKeyException() {
        super("缺少 API key。请在 Authorization 头里带上 Bearer <key>。");
    }
}
