package com.modelgate.ratelimit;

/**
 * 请求被限流或超出配额。
 *
 * 带 {@code retryAfterMs}，因为它要变成 HTTP 的 {@code Retry-After} 头 ——
 * 告诉客户端"什么时候可以再试"。没有这个头，客户端只能盲目重试，
 * 在限流场景下盲目重试等于火上浇油。
 */
public class RateLimitedException extends RuntimeException {

    /** 给客户端和监控用的机器可读错误码。 */
    private final String code;

    /** 触发的具体限制：requests / daily_tokens / monthly_tokens。 */
    private final String limitType;

    /** 建议等待毫秒数；未知时为 -1。 */
    private final long retryAfterMs;

    private final long remaining;

    public RateLimitedException(String code, String limitType, long retryAfterMs, long remaining) {
        super(buildMessage(code, limitType, retryAfterMs));
        this.code = code;
        this.limitType = limitType;
        this.retryAfterMs = retryAfterMs;
        this.remaining = remaining;
    }

    private static String buildMessage(String code, String limitType, long retryAfterMs) {
        String base = "quota_exceeded".equals(code)
                ? "已超出用量配额（" + limitType + "）"
                : "请求过于频繁，已触发限流";
        if (retryAfterMs > 0) {
            return base + "，请等待约 " + retryAfterMs + "ms 后重试";
        }
        return base;
    }

    public String code() {
        return code;
    }

    public String limitType() {
        return limitType;
    }

    public long retryAfterMs() {
        return retryAfterMs;
    }

    public long remaining() {
        return remaining;
    }
}
