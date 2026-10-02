package com.modelgate.provider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * API 密钥池 —— 轮询 + 按密钥冷却。
 *
 * 解决什么问题：
 *   1. 单个密钥的速率限制是有限的。多把密钥轮着用能把可用配额叠起来。
 *   2. 某把密钥失效（被吊销、超额）时，不应该让整个供应商跟着挂掉 ——
 *      把坏的那把冷却掉，其余的继续服务。
 *
 * 本类【必须线程安全】：容错层会从多个线程并发调用。
 *
 * ⚠️ 安全要求：密钥【绝不能】出现在日志或接口返回里。
 *    所有对外暴露的地方都必须走 {@link #mask(String)}。
 *    把完整密钥打进日志，等于把它泄露给了所有能看日志的人 ——
 *    这是最常见的密钥泄露途径之一。
 */
public class ApiKeyPool {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyPool.class);

    private static final class KeyState {
        final String key;
        final AtomicLong uses = new AtomicLong();
        final AtomicLong failures = new AtomicLong();
        /** 冷却到这个时刻为止；0 表示可用。 */
        volatile long cooldownUntil = 0L;
        volatile String lastErrorType;

        KeyState(String key) {
            this.key = key;
        }

        boolean healthy(long now) {
            return cooldownUntil <= now;
        }
    }

    private final List<KeyState> keys;
    private final AtomicInteger cursor = new AtomicInteger();

    /** 鉴权失败（密钥失效）的冷却时长。密钥不会自己恢复，所以冷却要长。 */
    private final long authCooldownMs;

    /** 限流的冷却时长。只是"你太快了"，短冷却后还能用。 */
    private final long rateLimitCooldownMs;

    public ApiKeyPool(List<String> rawKeys, long authCooldownMs, long rateLimitCooldownMs) {
        // LinkedHashSet 去重同时保留顺序 —— 重复配置同一把密钥会让轮询失去意义
        LinkedHashSet<String> distinct = new LinkedHashSet<>();
        for (String k : rawKeys) {
            if (k != null && !k.isBlank()) {
                distinct.add(k.trim());
            }
        }
        List<KeyState> states = new ArrayList<>();
        for (String k : distinct) {
            states.add(new KeyState(k));
        }
        this.keys = List.copyOf(states);
        this.authCooldownMs = authCooldownMs;
        this.rateLimitCooldownMs = rateLimitCooldownMs;

        log.info("密钥池已建立: 共 {} 把密钥 {}", keys.size(),
                keys.stream().map(s -> mask(s.key)).toList());
    }

    public int size() {
        return keys.size();
    }

    /**
     * 取一把当前可用的密钥，轮询推进。
     *
     * @return 可用的密钥；全部处于冷却中时返回 null
     */
    public String acquire() {
        int n = keys.size();
        if (n == 0) {
            return null;
        }
        long now = System.currentTimeMillis();
        // 从游标处开始找，最多绕一圈 —— 避免总是撞在冷却中的那把上
        for (int i = 0; i < n; i++) {
            KeyState state = keys.get(Math.floorMod(cursor.getAndIncrement(), n));
            if (state.healthy(now)) {
                state.uses.incrementAndGet();
                return state.key;
            }
        }
        return null;
    }

    public void reportSuccess(String key) {
        KeyState state = find(key);
        if (state != null) {
            state.cooldownUntil = 0L;
        }
    }

    /**
     * 上报一次失败，按错误类型决定是否冷却这把密钥。
     *
     * 只有【密钥相关】的错误才冷却密钥：
     *   401 / 403 → 这把密钥失效了，冷却久一点
     *   429       → 这把密钥被限流了，短冷却后还能用
     *   5xx       → 是上游整体的问题，跟这把密钥无关，不该冷却它
     *               （冷却了反而会让所有密钥一起被误伤）
     */
    public void reportFailure(String key, String errorType) {
        KeyState state = find(key);
        if (state == null) {
            return;
        }
        state.failures.incrementAndGet();
        state.lastErrorType = errorType;

        long now = System.currentTimeMillis();
        if ("upstream_401".equals(errorType) || "upstream_403".equals(errorType)) {
            state.cooldownUntil = now + authCooldownMs;
            log.warn("密钥 {} 鉴权失败，冷却 {}ms", mask(key), authCooldownMs);
        } else if ("rate_limited".equals(errorType)) {
            state.cooldownUntil = now + rateLimitCooldownMs;
            log.warn("密钥 {} 被限流，冷却 {}ms", mask(key), rateLimitCooldownMs);
        }
    }

    /**
     * 除指定密钥外，还有没有可用的密钥。
     *
     * 这个方法决定了 401 要不要重试：
     *   - 只有一把密钥 → 重试毫无意义（还是这把）→ 不可重试
     *   - 还有别的密钥 → 重试会换一把 → 值得重试
     *
     * 这是密钥池给容错层带来的新维度："同样的重试，换一个凭证"。
     */
    public boolean hasHealthyKeyOtherThan(String key) {
        long now = System.currentTimeMillis();
        for (KeyState state : keys) {
            if (!state.key.equals(key) && state.healthy(now)) {
                return true;
            }
        }
        return false;
    }

    /** 全部密钥是否都在冷却中。 */
    public boolean allCooling() {
        long now = System.currentTimeMillis();
        for (KeyState state : keys) {
            if (state.healthy(now)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 供管理接口查看的状态。**密钥一律脱敏。**
     */
    public List<Map<String, Object>> stats() {
        long now = System.currentTimeMillis();
        List<Map<String, Object>> list = new ArrayList<>();
        for (KeyState state : keys) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", mask(state.key));
            m.put("healthy", state.healthy(now));
            m.put("cooldownRemainingMs", Math.max(0, state.cooldownUntil - now));
            m.put("uses", state.uses.get());
            m.put("failures", state.failures.get());
            m.put("lastErrorType", state.lastErrorType);
            list.add(m);
        }
        return list;
    }

    private KeyState find(String key) {
        for (KeyState state : keys) {
            if (state.key.equals(key)) {
                return state;
            }
        }
        return null;
    }

    /**
     * 密钥脱敏。只保留头尾各 4 位，中间用省略号。
     *
     * 这样既能让人分辨"是哪一把密钥出的问题"（多密钥场景下的刚需），
     * 又不足以还原出完整密钥。
     * 太短的直接全遮掉 —— 否则等于没遮。
     */
    public static String mask(String key) {
        if (key == null || key.isBlank()) {
            return "(空)";
        }
        if (key.length() <= 12) {
            return "****";
        }
        return key.substring(0, 4) + "…" + key.substring(key.length() - 4);
    }
}
