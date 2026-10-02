package com.modelgate.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.modelgate.provider.ChatMessage;
import com.modelgate.provider.ProviderRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 响应缓存。
 *
 * ============================================================================
 * 最重要的一条：不要缓存非确定性接口
 * ============================================================================
 * LLM 在 temperature > 0 时，同样的输入会产生**不同的输出**。这是它的特性，
 * 不是缺陷 —— 用户要的就是多样性（比如"给我起 10 个标题"）。
 *
 * 如果你按 prompt 缓存了它，第二次请求会拿到和第一次一模一样的答案：
 *   - 语义上错了：用户要 10 个不同标题，你每次都给他同一个
 *   - 而且很难发现：功能"能用"，只是变得诡异
 *
 * 所以默认只在 temperature == 0（明确要求确定性）时才缓存。
 * **temperature 未指定时也不能缓存** —— 那时用的是供应商的默认值（通常是 1），
 * 依然是随机的。
 *
 * ============================================================================
 * 第二个坑：缓存键的构造必须是一一映射的
 * ============================================================================
 * 最直觉的写法是用分隔符把字段拼起来：
 *
 *     "msg:" + role + ":" + content + "\n"
 *
 * 这个写法是有 bug 的。考虑两组不同的请求：
 *
 *   A: messages = [("user", "a\nmsg:assistant:b")]
 *   B: messages = [("user", "a"), ("assistant", "b")]
 *
 * 两者拼出来的字符串完全相同：
 *
 *     "msg:user:a\nmsg:assistant:b\n"
 *
 * 于是**两个不同的请求命中同一个缓存**，B 会拿到 A 的答案。
 * 内容里带换行就能触发，而 LLM 的 prompt 里带换行太常见了。
 *
 * 正确的做法是**长度前缀**：先写长度、再写内容，从语法上就不可能歧义。
 * 这是序列化格式设计的通用原则（protobuf、netstring 都这么做）。
 *
 * ============================================================================
 * 第三个坑：缓存击穿（cache stampede）
 * ============================================================================
 * 一个热点键过期的瞬间，N 个并发请求同时发现"缓存没了"，
 * 然后**一起去调用上游**。上游看到的是 N 倍的突发流量，
 * 缓存不但没起到保护作用，反而制造了尖峰。
 *
 * 解法是单飞（single-flight）：只让第一个请求去加载，其余等待它的结果。
 * 见 {@link #tryBeginLoad} / {@link #awaitLoad}。
 * ============================================================================
 */
@Service
public class ResponseCache {

    private static final Logger log = LoggerFactory.getLogger(ResponseCache.class);

    private final StringRedisTemplate redis;
    private final CacheProperties props;
    private final ObjectMapper mapper;

    /** 正在加载中的键 -> 等待者。单飞机制的核心。 */
    private final ConcurrentHashMap<String, CompletableFuture<CachedResponse>> inFlight =
            new ConcurrentHashMap<>();

    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();
    private final AtomicLong puts = new AtomicLong();
    private final AtomicLong skipped = new AtomicLong();
    private final AtomicLong coalesced = new AtomicLong();
    private final AtomicLong errors = new AtomicLong();

    public ResponseCache(StringRedisTemplate redis, CacheProperties props, ObjectMapper mapper) {
        this.redis = redis;
        this.props = props;
        this.mapper = mapper;
        log.info("响应缓存已就绪: 启用={} TTL={}s 键含租户={} 仅确定性={}",
                props.isEnabled(), props.getTtlSeconds(), props.isIncludeTenant(), props.isOnlyDeterministic());
    }

    /**
     * 这个请求是否值得缓存。
     *
     * 判断逻辑本身就是一份"什么不该缓存"的清单：
     *   - 缓存关闭
     *   - temperature 不是 0（非确定性，见类注释）
     *   - 没有消息
     */
    public boolean isCacheable(ProviderRequest request) {
        if (!props.isEnabled()) {
            return false;
        }
        if (props.isOnlyDeterministic()) {
            Double temperature = request.temperature();
            // 注意 null 也要拒绝：未指定时用的是供应商默认值（通常 1.0），依然是随机的
            if (temperature == null || temperature != 0.0d) {
                skipped.incrementAndGet();
                return false;
            }
        }
        return request.messages() != null && !request.messages().isEmpty();
    }

    /** 查缓存。任何异常都当作未命中 —— 缓存故障不该影响业务。 */
    public CachedResponse get(String key) {
        if (!props.isEnabled()) {
            return null;
        }
        try {
            String json = redis.opsForValue().get(key);
            if (json == null) {
                misses.incrementAndGet();
                return null;
            }
            CachedResponse cached = mapper.readValue(json, CachedResponse.class);
            hits.incrementAndGet();
            return cached;
        } catch (Exception e) {
            // 反序列化失败（比如缓存格式版本变了）也按未命中处理，并顺手删掉脏数据
            errors.incrementAndGet();
            log.warn("读取缓存失败，按未命中处理: {}", e.toString());
            try {
                redis.delete(key);
            } catch (Exception ignored) {
                // 删除失败无所谓，TTL 到了自然会消失
            }
            return null;
        }
    }

    /** 写缓存。失败只记数，不影响请求。 */
    public void put(String key, CachedResponse value) {
        if (!props.isEnabled() || value == null || !value.isUsable()) {
            return;
        }
        try {
            String json = mapper.writeValueAsString(value);
            redis.opsForValue().set(key, json, Duration.ofSeconds(props.getTtlSeconds()));
            puts.incrementAndGet();
        } catch (Exception e) {
            errors.incrementAndGet();
            log.warn("写入缓存失败（不影响请求）: {}", e.toString());
        }
    }

    // ------------------------------------------------------------------ 单飞（防击穿）

    /**
     * 尝试成为该键的"加载者"。
     *
     * @return true 表示由你去加载上游，其他并发请求会等你的结果
     */
    public boolean tryBeginLoad(String key) {
        CompletableFuture<CachedResponse> mine = new CompletableFuture<>();
        boolean won = inFlight.putIfAbsent(key, mine) == null;
        if (!won) {
            coalesced.incrementAndGet();
        }
        return won;
    }

    /**
     * 加载结束，唤醒所有等待者。
     *
     * ⚠️ 必须放在 finally 里调用。忘记调用的话，等待者会一直挂到超时 ——
     * 虽然 {@link #awaitLoad} 有超时兜底，但那意味着白白浪费了几秒钟。
     */
    public void finishLoad(String key, CachedResponse value) {
        CompletableFuture<CachedResponse> future = inFlight.remove(key);
        if (future != null) {
            future.complete(value);   // value 允许为 null（表示加载失败）
        }
    }

    /**
     * 等待别的请求把结果加载出来。
     *
     * @return 加载到的结果；等待超时或加载失败时返回 null
     */
    public CachedResponse awaitLoad(String key, long waitMs) {
        CompletableFuture<CachedResponse> future = inFlight.get(key);
        if (future == null) {
            // 加载方已经完成了，直接再查一次缓存
            return get(key);
        }
        try {
            return future.get(waitMs, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            // 等超时了 —— 自己也去加载，绝不无限等待
            return null;
        }
    }

    // ------------------------------------------------------------------ 键构造

    /**
     * 构造缓存键。
     *
     * 用**长度前缀**保证一一映射，理由见类注释。
     */
    public String keyFor(ProviderRequest request, String tenant) {
        StringBuilder sb = new StringBuilder(256);
        appendField(sb, "model", request.model());
        appendField(sb, "temperature", String.valueOf(request.temperature()));
        appendField(sb, "maxTokens", String.valueOf(request.maxTokens()));
        if (props.isIncludeTenant()) {
            appendField(sb, "tenant", tenant == null ? "anonymous" : tenant);
        }
        for (ChatMessage m : request.messages()) {
            appendField(sb, "role", m.role());
            appendField(sb, "content", m.content());
        }
        return props.getKeyPrefix() + sha256Hex(sb.toString());
    }

    /** 长度前缀写入：`字段名[长度]:内容` —— 内容里有任何字符都不会歧义。 */
    private void appendField(StringBuilder sb, String name, String value) {
        String v = value == null ? "" : value;
        sb.append(name).append('[').append(v.length()).append("]:").append(v).append(';');
    }

    private String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    // ------------------------------------------------------------------ 观测

    public Map<String, Object> stats() {
        long h = hits.get();
        long m = misses.get();
        long total = h + m;
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("enabled", props.isEnabled());
        stats.put("ttlSeconds", props.getTtlSeconds());
        stats.put("includeTenant", props.isIncludeTenant());
        stats.put("onlyDeterministic", props.isOnlyDeterministic());
        stats.put("hits", h);
        stats.put("misses", m);
        stats.put("hitRate", total == 0 ? 0.0 : Math.round(h * 10000.0 / total) / 100.0);
        stats.put("puts", puts.get());
        stats.put("skippedNotCacheable", skipped.get());
        stats.put("coalesced", coalesced.get());
        stats.put("errors", errors.get());
        stats.put("inFlight", inFlight.size());
        return stats;
    }

    public void resetStats() {
        hits.set(0);
        misses.set(0);
        puts.set(0);
        skipped.set(0);
        coalesced.set(0);
        errors.set(0);
    }

    public long hitCount() {
        return hits.get();
    }

    public long missCount() {
        return misses.get();
    }

    public long coalescedCount() {
        return coalesced.get();
    }
}
