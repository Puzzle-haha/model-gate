package com.modelgate.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 网关指标。
 *
 * ============================================================================
 * 为什么用 Micrometer 而不是自己维护一堆 AtomicLong
 * ============================================================================
 * 自己数也能数出来，但你会失去三件事：
 *   1. **百分位数**。平均值会骗人 —— 99 个请求 10ms、1 个请求 10s，
 *      平均值只有 110ms，看起来很好，但那个倒霉用户等了 10 秒。
 *      Micrometer 的 Timer 直接给你 P50/P95/P99。
 *   2. **导出格式**。Prometheus / Graphite / Datadog 各有各的格式，
 *      Micrometer 抽象掉了这一层，换个监控系统不用改业务代码。
 *   3. **标签维度**。按 provider / model / outcome 打标签，
 *      才能回答"是哪个供应商变慢了"而不是只知道"整体变慢了"。
 *
 * ============================================================================
 * ⚠️ 标签基数（cardinality）的坑
 * ============================================================================
 * 指标标签的**每一个不同组合**都会在内存里创建一个独立的计量器，
 * 而且是永久保留的。如果拿用户 ID、请求 ID、完整 prompt 当标签，
 * 内存会被打爆 —— 这是 Prometheus 使用中最经典的事故。
 *
 * 所以这里的标签只用了 provider、model、outcome 这类**取值范围有限**的维度。
 * 想追踪单次请求，用日志和 traceId，不要用指标。
 */
@Component
public class GatewayMetrics {

    /** 计量器缓存：避免每次请求都去注册表里查一遍（那里有锁）。 */
    private final ConcurrentHashMap<String, Counter> counters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Timer> timers = new ConcurrentHashMap<>();

    private final MeterRegistry registry;

    public GatewayMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * 记录一次网关调用。
     *
     * @param outcome success / degraded
     */
    public void recordCall(String provider, String model, String outcome,
                           long elapsedMs, long costMicros) {
        counter("modelgate.calls", "provider", provider, "model", model, "outcome", outcome)
                .increment();

        timer("modelgate.call.duration", "provider", provider, "model", model)
                .record(Duration.ofMillis(Math.max(0, elapsedMs)));

        if (costMicros > 0) {
            counter("modelgate.cost.micros", "model", model).increment(costMicros);
        }
    }

    /** 记录缓存结果：hit / miss / coalesced。 */
    public void recordCache(String result) {
        counter("modelgate.cache", "result", result).increment();
    }

    /** 记录被限流/配额拒绝的请求。 */
    public void recordRejected(String limitType) {
        counter("modelgate.rejected", "limit_type", limitType).increment();
    }

    /** 记录一次熔断快速失败。 */
    public void recordCircuitOpen(String provider, String model) {
        counter("modelgate.circuit.open", "provider", provider, "model", model).increment();
    }

    // ------------------------------------------------------------------

    private Counter counter(String name, String... tags) {
        String key = name + '|' + String.join(",", tags);
        return counters.computeIfAbsent(key, k -> Counter.builder(name).tags(tags).register(registry));
    }

    private Timer timer(String name, String... tags) {
        String key = name + '|' + String.join(",", tags);
        return timers.computeIfAbsent(key, k -> Timer.builder(name)
                .tags(tags)
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry));
    }
}
