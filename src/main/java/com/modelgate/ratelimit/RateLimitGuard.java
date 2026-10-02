package com.modelgate.ratelimit;

import com.modelgate.observability.GatewayMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 限流与配额的门卫：请求进入网关时先过这一关。
 *
 * 位置很关键 —— 它必须在**任何上游调用之前**执行。
 * 放在后面就失去意义了：那时候配额已经花掉了，拒绝只是"事后通知"。
 * 面试问"限流放在哪一层"，答案就是"越靠前越好，最好在路由之前"。
 */
@Service
public class RateLimitGuard {

    private static final Logger log = LoggerFactory.getLogger(RateLimitGuard.class);

    /** 没带 API key 的调用方共用的标识。 */
    private static final String ANONYMOUS = "anonymous";

    private final TokenBucketRateLimiter limiter;
    private final QuotaService quota;
    private final GatewayMetrics metrics;

    public RateLimitGuard(TokenBucketRateLimiter limiter, QuotaService quota,
                          GatewayMetrics metrics) {
        this.limiter = limiter;
        this.quota = quota;
        this.metrics = metrics;
    }

    /**
     * 准入检查。不通过就抛 {@link RateLimitedException}。
     *
     * @param apiKey 调用方凭证，可为 null（视为匿名）
     * @return 用于后续记账的租户标识
     */
    public String checkOrThrow(String apiKey) {
        String tenant = tenantKey(apiKey);

        // ---- 1. 速率限制（短周期，保护上游不被瞬时打爆）----
        RateLimitDecision decision = limiter.tryAcquire(tenant, 1);
        if (decision.degraded()) {
            log.warn("限流组件不可用，本次按 fail-open 放行（tenant={}）", tenant);
        } else if (!decision.allowed()) {
            // 被拒绝的请求也计入指标 —— 否则"限流到底拦了多少"没有数据支撑，
            // 面试时只能说"我实现了限流"，说不出"它拦住了多少无效调用"
            metrics.recordRejected("requests");
            throw new RateLimitedException("rate_limit_exceeded", "requests",
                    decision.retryAfterMs(), decision.remaining());
        }

        // ---- 2. 用量配额（长周期，防止某个租户失控烧钱）----
        String exceeded = quota.checkExceeded(tenant);
        if (exceeded != null) {
            metrics.recordRejected(exceeded);
            throw new RateLimitedException("quota_exceeded", exceeded, -1, 0);
        }

        return tenant;
    }

    /** 调用完成后累加实际消耗。 */
    public void recordUsage(String tenant, Integer totalTokens) {
        if (tenant == null || totalTokens == null || totalTokens <= 0) {
            return;
        }
        quota.recordUsage(tenant, totalTokens);
    }

    /**
     * 把 API key 转成用于 Redis 键的租户标识。
     *
     * ⚠️ 【绝对不能】把原始 key 放进 Redis 键。原因：
     *   1. Redis 的键名在 MONITOR、SLOWLOG、KEYS 命令里都是明文可见的，
     *      任何能连 Redis 的人（包括运维、排障工具、日志采集）都会看到你的密钥
     *   2. 键名里带密钥，等于把密钥复制到了另一个存储里，
     *      "撤销密钥"时要清理的地方又多了一处
     *
     * 用 SHA-256 前 16 位十六进制：足够区分不同租户，
     * 又无法从哈希反推出密钥。这和"密码存哈希"是同一个思路。
     */
    public String tenantKey(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return ANONYMOUS;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(apiKey.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 必须支持的算法，走不到这里
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    public TokenBucketRateLimiter limiter() {
        return limiter;
    }

    public QuotaService quotaService() {
        return quota;
    }
}
