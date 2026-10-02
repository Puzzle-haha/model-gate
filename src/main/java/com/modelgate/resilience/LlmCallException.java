package com.modelgate.resilience;

/**
 * 上游调用失败。
 *
 * 带一个 {@code retryable} 标记，这个设计很关键：
 * 上游必须能区分「重试有意义」和「重试纯属浪费」。
 *
 * 4xx（密钥错、参数错）重试一万次结果都一样，只会白白放大对上游的压力；
 * 5xx、超时、空响应才值得重试。很多线上的「重试风暴」事故，
 * 根源就是没做这个区分。
 */
public class LlmCallException extends Exception {

    private final boolean retryable;
    private final String errorType;

    public LlmCallException(String message, boolean retryable) {
        this(message, retryable, retryable ? "upstream_error" : "upstream_client_error");
    }

    public LlmCallException(String message, boolean retryable, String errorType) {
        super(message);
        this.retryable = retryable;
        this.errorType = errorType;
    }

    public boolean isRetryable() {
        return retryable;
    }

    public String errorType() {
        return errorType;
    }
}
