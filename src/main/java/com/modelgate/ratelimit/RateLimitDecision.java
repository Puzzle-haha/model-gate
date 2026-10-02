package com.modelgate.ratelimit;

/**
 * 一次限流判定的结果。
 *
 * @param allowed      是否放行
 * @param remaining    桶里剩余令牌数
 * @param retryAfterMs 被拒时建议等待多久再试（毫秒）；放行时为 0
 * @param degraded     限流组件自身不可用而走了降级放行 —— 这个标记很重要，
 *                     它让"因为超限被拒"和"因为限流挂了所以放行"在监控里可区分
 */
public record RateLimitDecision(
        boolean allowed,
        long remaining,
        long retryAfterMs,
        boolean degraded) {

    public static RateLimitDecision allow(long remaining) {
        return new RateLimitDecision(true, remaining, 0, false);
    }

    public static RateLimitDecision deny(long remaining, long retryAfterMs) {
        return new RateLimitDecision(false, remaining, retryAfterMs, false);
    }

    public static RateLimitDecision degradedAllow() {
        return new RateLimitDecision(true, -1, 0, true);
    }
}
