package com.modelgate.ratelimit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 用量配额：按天 / 按 token 数累计，超出即拒绝。
 *
 * ============================================================================
 * 必须先说清楚它的一个固有局限
 * ============================================================================
 * 配额的"检查"发生在调用【之前】，用量的"累加"发生在调用【之后】。
 * 所以在检查通过但还没累加的这段时间里，并发请求能一起挤过去：
 *
 *   已有用量 999/1000，10 个并发请求同时检查 → 全部通过
 *   → 最终用量 1000 + 这 10 次的实际消耗，超出配额
 *
 * 这叫**最终一致**的配额，超出的幅度由「并发度 × 单次消耗」决定。
 * 想彻底避免只能预留额度（检查时就先扣一个预估量），但那样要么估不准、
 * 要么失败后还得退还，复杂度陡增。
 *
 * 对"防止某个租户失控烧钱"这个目标来说，最终一致完全够用 ——
 * 超出几个百分点无关紧要。**关键是想清楚你的目标是什么，
 * 而不是追求一个听起来更严谨的机制。**
 */
@Service
public class QuotaService {

    private static final Logger log = LoggerFactory.getLogger(QuotaService.class);

    /**
     * 配额固定用北京时区。
     *
     * 为什么不跟随服务器时区：服务器时区一改（或者部署到别的区域），
     * 配额的重置时刻就会平移，用户在"一天"中间被重置，账单对不上。
     * 配额边界必须是业务决定的常量，不能是环境决定的变量。
     */
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private static final String INCR_SCRIPT = """
            local key = KEYS[1]
            local amount = tonumber(ARGV[1])
            local ttl = tonumber(ARGV[2])
            local v = redis.call('INCRBY', key, amount)
            -- 只在键还没有 TTL 时设置，避免每次调用都刷新过期时间
            -- （如果每次都刷新，这个键就永远不会过期，内存只增不减）
            if redis.call('TTL', key) < 0 then
              redis.call('EXPIRE', key, ttl)
            end
            return v
            """;

    @SuppressWarnings("rawtypes")
    private static final RedisScript<Long> INCR_SCRIPT_OBJ =
            new DefaultRedisScript<>(INCR_SCRIPT, Long.class);

    private final StringRedisTemplate redis;
    private final RateLimitProperties props;

    public QuotaService(StringRedisTemplate redis, RateLimitProperties props) {
        this.redis = redis;
        this.props = props;
        log.info("配额已就绪: 每日={} tokens 每月={} tokens",
                props.getDailyTokens() > 0 ? props.getDailyTokens() : "不限",
                props.getMonthlyTokens() > 0 ? props.getMonthlyTokens() : "不限");
    }

    /**
     * 检查是否还有配额。
     *
     * @return 超出时返回被触发的限制类型，未超出返回 null
     */
    public String checkExceeded(String tenant) {
        if (!props.isQuotaEnabled()) {
            return null;
        }
        try {
            String dailyKey = dailyKey(tenant);
            String monthlyKey = monthlyKey(tenant);

            long dailyUsed = parse(redis.opsForValue().get(dailyKey));
            long monthlyUsed = parse(redis.opsForValue().get(monthlyKey));

            if (props.getDailyTokens() > 0 && dailyUsed >= props.getDailyTokens()) {
                return "daily_tokens";
            }
            if (props.getMonthlyTokens() > 0 && monthlyUsed >= props.getMonthlyTokens()) {
                return "monthly_tokens";
            }
            return null;
        } catch (Exception e) {
            // 和限流一样：配额组件不可用时按 fail-open 处理。
            // 这里刻意返回 null（放行），并在日志里留痕。
            log.warn("配额检查失败（Redis 异常），按 fail-open 放行: {}", e.toString());
            return null;
        }
    }

    /**
     * 累加用量。调用失败不能影响主流程 —— 配额记少了一点，
     * 远不如"因为记账失败而让用户请求失败"严重。
     */
    public void recordUsage(String tenant, long tokens) {
        if (!props.isQuotaEnabled() || tokens <= 0) {
            return;
        }
        try {
            Instant now = Instant.now();
            increment(dailyKey(tenant), tokens, secondsUntilEndOfDay(now));
            increment(monthlyKey(tenant), tokens, secondsUntilEndOfMonth(now));
        } catch (Exception e) {
            log.warn("配额累加失败（不影响请求）: {}", e.toString());
        }
    }

    /** 查询某租户的当前用量。 */
    public Map<String, Object> usage(String tenant) {
        Map<String, Object> m = new LinkedHashMap<>();
        try {
            long dailyUsed = parse(redis.opsForValue().get(dailyKey(tenant)));
            long monthlyUsed = parse(redis.opsForValue().get(monthlyKey(tenant)));

            m.put("tenant", tenant);
            m.put("dailyUsed", dailyUsed);
            m.put("dailyLimit", props.getDailyTokens());
            m.put("dailyRemaining", props.getDailyTokens() > 0
                    ? Math.max(0, props.getDailyTokens() - dailyUsed) : -1);
            m.put("monthlyUsed", monthlyUsed);
            m.put("monthlyLimit", props.getMonthlyTokens());
            m.put("monthlyRemaining", props.getMonthlyTokens() > 0
                    ? Math.max(0, props.getMonthlyTokens() - monthlyUsed) : -1);
        } catch (Exception e) {
            m.put("error", e.toString());
        }
        return m;
    }

    /** 重置某租户的配额（管理接口用）。 */
    public void reset(String tenant) {
        redis.delete(List.of(dailyKey(tenant), monthlyKey(tenant)));
    }

    // ------------------------------------------------------------------ 内部

    private void increment(String key, long amount, long ttlSeconds) {
        redis.execute(INCR_SCRIPT_OBJ, Collections.singletonList(key),
                String.valueOf(amount), String.valueOf(Math.max(60, ttlSeconds)));
    }

    private String dailyKey(String tenant) {
        String day = ZonedDateTime.now(ZONE).toLocalDate().toString().replace("-", "");
        return props.getKeyPrefix() + "quota:" + tenant + ":d:" + day;
    }

    private String monthlyKey(String tenant) {
        ZonedDateTime now = ZonedDateTime.now(ZONE);
        String month = String.format("%04d%02d", now.getYear(), now.getMonthValue());
        return props.getKeyPrefix() + "quota:" + tenant + ":m:" + month;
    }

    private long parse(String value) {
        if (value == null || value.isEmpty()) {
            return 0L;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private long secondsUntilEndOfDay(Instant now) {
        ZonedDateTime z = now.atZone(ZONE);
        ZonedDateTime end = z.toLocalDate().plusDays(1).atStartOfDay(ZONE);
        return Math.max(60, Duration.between(z, end).getSeconds());
    }

    private long secondsUntilEndOfMonth(Instant now) {
        ZonedDateTime z = now.atZone(ZONE);
        ZonedDateTime end = z.toLocalDate().withDayOfMonth(1).plusMonths(1).atStartOfDay(ZONE);
        return Math.max(60, Duration.between(z, end).getSeconds());
    }
}
