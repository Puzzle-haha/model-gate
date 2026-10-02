package com.modelgate.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 限流与配额配置。
 */
@Component
@ConfigurationProperties(prefix = "modelgate.ratelimit")
public class RateLimitProperties {

    private boolean enabled = true;

    /**
     * 令牌桶容量 —— 允许的**突发**请求数。
     *
     * 这个值和「每秒补充速率」是两回事，理解它们的区别是理解令牌桶的关键：
     *   capacity = 20, refill = 5/s
     *   → 长期平均 5 QPS，但允许瞬间打 20 个（把之前攒的额度一次用完）
     *
     * 固定窗口限流做不到这种"攒额度"的效果，要么限制过死，要么在窗口边界
     * 被瞬间打两倍流量（窗口切换时两个窗口各放行一批）。
     */
    private int capacity = 20;

    /** 每秒补充多少令牌，即长期平均速率。 */
    private int refillPerSecond = 5;

    /**
     * Redis 不可用时是否放行（fail-open）。
     *
     * 这是限流设计里最需要想清楚的一个取舍：
     *   true  （fail-open）  —— 保护的是【可用性】：限流组件挂了，服务照常。
     *                          代价是 Redis 故障期间限流失效，上游可能被打。
     *   false （fail-closed）—— 保护的是【上游】：限流挂了就拒绝一切。
     *                          代价是 Redis 抖动会直接变成全站不可用。
     *
     * 默认选 fail-open：限流是"保护措施"，不该成为"单点故障源"。
     * 但如果你更怕上游被打挂（比如按量付费、上游有硬性配额），
     * 就应该改成 false —— 这是业务判断，不是技术判断。
     */
    private boolean failOpen = true;

    /** Redis 键前缀，避免和同实例上的其他业务撞键。 */
    private String keyPrefix = "mg:rl:";

    /** 是否启用配额（按天/按月累计用量）。 */
    private boolean quotaEnabled = true;

    /** 每日 token 配额。<= 0 表示不限制。 */
    private long dailyTokens = 200_000;

    /** 每月 token 配额。<= 0 表示不限制。 */
    private long monthlyTokens = 3_000_000;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public int getCapacity() { return capacity; }
    public void setCapacity(int capacity) { this.capacity = capacity; }

    public int getRefillPerSecond() { return refillPerSecond; }
    public void setRefillPerSecond(int refillPerSecond) { this.refillPerSecond = refillPerSecond; }

    public boolean isFailOpen() { return failOpen; }
    public void setFailOpen(boolean failOpen) { this.failOpen = failOpen; }

    public String getKeyPrefix() { return keyPrefix; }
    public void setKeyPrefix(String keyPrefix) { this.keyPrefix = keyPrefix; }

    public boolean isQuotaEnabled() { return quotaEnabled; }
    public void setQuotaEnabled(boolean quotaEnabled) { this.quotaEnabled = quotaEnabled; }

    public long getDailyTokens() { return dailyTokens; }
    public void setDailyTokens(long dailyTokens) { this.dailyTokens = dailyTokens; }

    public long getMonthlyTokens() { return monthlyTokens; }
    public void setMonthlyTokens(long monthlyTokens) { this.monthlyTokens = monthlyTokens; }
}
