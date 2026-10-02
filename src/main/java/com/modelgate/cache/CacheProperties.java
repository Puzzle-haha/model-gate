package com.modelgate.cache;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "modelgate.cache")
public class CacheProperties {

    private boolean enabled = true;

    /** 缓存存活时间（秒）。 */
    private long ttlSeconds = 300;

    /**
     * 缓存键是否包含调用方。
     *
     * 包含（默认）：更安全，一个租户的响应不会被另一个租户命中。
     *               代价是命中率下降——同样的 prompt 不同租户要各存一份。
     * 不包含：       命中率高，但如果 prompt 里含租户私有数据，
     *               理论上存在跨租户读到同一份缓存的风险
     *               （虽然需要 prompt 完全一致才会撞上，风险很低）。
     *
     * 默认选安全的那个。**缓存带来的性能收益，不值得用数据隔离风险去换。**
     */
    private boolean includeTenant = true;

    /**
     * 是否只缓存确定性请求（temperature == 0）。
     *
     * 这是本模块最重要的一个开关，理由见 {@link ResponseCache} 的类注释。
     * 默认 true —— 关掉它意味着你在缓存一个非确定性接口，语义上是错的。
     */
    private boolean onlyDeterministic = true;

    /**
     * 单飞（single-flight）等待其他请求加载完成的最长时间。
     *
     * 超时后自己也去加载，避免因为加载方异常退出而永久等待。
     */
    private long loadWaitMs = 3000;

    private String keyPrefix = "mg:cache:";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public long getTtlSeconds() { return ttlSeconds; }
    public void setTtlSeconds(long ttlSeconds) { this.ttlSeconds = ttlSeconds; }

    public boolean isIncludeTenant() { return includeTenant; }
    public void setIncludeTenant(boolean includeTenant) { this.includeTenant = includeTenant; }

    public boolean isOnlyDeterministic() { return onlyDeterministic; }
    public void setOnlyDeterministic(boolean onlyDeterministic) { this.onlyDeterministic = onlyDeterministic; }

    public long getLoadWaitMs() { return loadWaitMs; }
    public void setLoadWaitMs(long loadWaitMs) { this.loadWaitMs = loadWaitMs; }

    public String getKeyPrefix() { return keyPrefix; }
    public void setKeyPrefix(String keyPrefix) { this.keyPrefix = keyPrefix; }
}
