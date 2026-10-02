package com.modelgate.ratelimit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * 基于 Redis + Lua 的令牌桶限流器。
 *
 * ============================================================================
 * 为什么必须用 Lua —— 这是本类唯一需要真正理解的东西
 * ============================================================================
 *
 * 令牌桶的逻辑是三步：**读当前令牌数 → 判断够不够 → 扣减**。
 * 如果分成三条独立命令发给 Redis，中间就会插入其他请求：
 *
 *   时刻   请求 A                请求 B              Redis 中的 tokens
 *   ------------------------------------------------------------------
 *   t1     读取 tokens = 1                              1
 *   t2                           读取 tokens = 1         1
 *   t3     1 >= 1，允许，扣减                            -
 *   t4                           1 >= 1，允许，扣减      -1   ← 放行了 2 个，但只有 1 个额度
 *
 * 这就是经典的 **check-then-act 竞态**。注意它在单机低并发时几乎不会出现，
 * 只在高并发或者多实例部署时才暴露 —— 也正是这种 bug 最难在测试环境发现。
 *
 * Lua 脚本在 Redis 中是**单线程原子执行**的：脚本开始后不会有其他命令插进来。
 * 所以「读-判断-扣减」变成一个不可分割的操作，上述交错不可能发生。
 * 而且只用一个网络往返，比 WATCH/MULTI 乐观锁重试简单得多、快得多。
 *
 * ============================================================================
 * 为什么用令牌桶而不是固定窗口
 * ============================================================================
 * 固定窗口（比如"每分钟 300 次"）会在窗口切换的瞬间被击穿：
 * 第 59 秒打满 300 次，第 61 秒又打满 300 次 —— 两秒内实际放行 600 次。
 * 令牌桶按时间连续补充，天然没有这个边界问题，而且能表达
 * 「平均 5 QPS，但允许瞬间突发 20 个」这种真实需求。
 */
@Component
public class TokenBucketRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(TokenBucketRateLimiter.class);

    /**
     * 令牌桶脚本。返回 {是否放行, 剩余令牌, 建议等待毫秒}。
     *
     * KEYS[1] 桶的键
     * ARGV[1] 容量 capacity
     * ARGV[2] 每毫秒补充的令牌数（refillPerSecond / 1000）
     * ARGV[3] 当前时间戳（毫秒，由客户端传入）
     * ARGV[4] 本次请求消耗的令牌数
     */
    private static final String SCRIPT = """
            local key = KEYS[1]
            local capacity = tonumber(ARGV[1])
            local refillPerMs = tonumber(ARGV[2])
            local now = tonumber(ARGV[3])
            local requested = tonumber(ARGV[4])

            local data = redis.call('HMGET', key, 'tokens', 'ts')
            local tokens = tonumber(data[1])
            local ts = tonumber(data[2])

            if tokens == nil then
              -- 首次访问：桶是满的
              tokens = capacity
              ts = now
            end

            -- 按经过的时间补充令牌，但不超过容量
            local elapsed = now - ts
            if elapsed < 0 then elapsed = 0 end
            tokens = math.min(capacity, tokens + elapsed * refillPerMs)

            local allowed = 0
            local retryAfterMs = 0

            if refillPerMs <= 0 then
              -- 退化为"纯容量"模式：不补充，只消耗
              if tokens >= requested then
                allowed = 1
                tokens = tokens - requested
              else
                retryAfterMs = 3600000
              end
            elseif tokens >= requested then
              allowed = 1
              tokens = tokens - requested
            else
              -- 还差多少令牌，就等多久
              retryAfterMs = math.ceil((requested - tokens) / refillPerMs)
            end

            redis.call('HSET', key, 'tokens', tokens, 'ts', now)
            -- 设置过期时间：桶从空到满所需时间 + 1 秒余量。
            -- 不设 TTL 的话，每个租户都会永久留下一个键，内存只增不减。
            local idleMs = 0
            if refillPerMs > 0 then
              idleMs = math.ceil(capacity / refillPerMs)
            end
            redis.call('PEXPIRE', key, idleMs + 1000)

            return {allowed, math.floor(tokens), retryAfterMs}
            """;

    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> SCRIPT_OBJ =
            new DefaultRedisScript<>(SCRIPT, List.class);

    private final StringRedisTemplate redis;
    private final RateLimitProperties props;

    public TokenBucketRateLimiter(StringRedisTemplate redis, RateLimitProperties props) {
        this.redis = redis;
        this.props = props;
        log.info("令牌桶限流器已就绪: 容量={} 补充={}/s failOpen={}",
                props.getCapacity(), props.getRefillPerSecond(), props.isFailOpen());
    }

    /**
     * 尝试获取令牌。
     *
     * @param bucketKey 桶标识（通常是租户或 API key 的哈希）
     * @param tokens   本次消耗的令牌数，一般是 1
     */
    public RateLimitDecision tryAcquire(String bucketKey, int tokens) {
        if (!props.isEnabled()) {
            return RateLimitDecision.allow(-1);
        }

        String key = props.getKeyPrefix() + "bucket:" + bucketKey;

        // 脚本内部统一用【毫秒】做时间单位（因为 System.currentTimeMillis 是毫秒），
        // 所以这里把「每秒补充量」换算成「每毫秒补充量」。
        // 结果通常是小数（5/s → 0.005/ms），Lua 的 tonumber 能正确处理。
        double refillPerMs = props.getRefillPerSecond() / 1000.0;

        try {
            @SuppressWarnings("unchecked")
            List<Long> result = redis.execute(SCRIPT_OBJ,
                    Collections.singletonList(key),
                    String.valueOf(props.getCapacity()),
                    String.valueOf(refillPerMs),
                    String.valueOf(System.currentTimeMillis()),
                    String.valueOf(tokens));

            if (result == null || result.size() < 3) {
                log.warn("令牌桶脚本返回异常结果: {}", result);
                return fallback();
            }

            boolean allowed = result.get(0) != null && result.get(0) == 1L;
            long remaining = result.get(1) == null ? 0 : result.get(1);
            long retryAfterMs = result.get(2) == null ? 0 : result.get(2);

            return allowed
                    ? RateLimitDecision.allow(remaining)
                    : RateLimitDecision.deny(remaining, retryAfterMs);

        } catch (Exception e) {
            // Redis 不可用、脚本执行失败、序列化异常…… 全部走同一个降级路径
            log.warn("令牌桶判定失败（Redis 异常）: {}", e.toString());
            return fallback();
        }
    }

    /** 按 failOpen 配置决定降级行为，并留下明确日志。 */
    private RateLimitDecision fallback() {
        if (props.isFailOpen()) {
            // 放行，但标记 degraded —— 让监控能看出"这段时间限流其实是失效的"
            return RateLimitDecision.degradedAllow();
        }
        // fail-closed：拒绝一切，并把等待时间设成一个较长的值，避免客户端疯狂重试
        return new RateLimitDecision(false, 0, 5000, true);
    }

    /** 清空某个桶（管理接口用，方便测试）。 */
    public void reset(String bucketKey) {
        redis.delete(props.getKeyPrefix() + "bucket:" + bucketKey);
    }
}
