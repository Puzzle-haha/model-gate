package com.modelgate.resilience;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 极简熔断器 —— 三态状态机。
 *
 * <pre>
 *           失败达到阈值
 *   CLOSED ──────────────► OPEN
 *     ▲                     │  冷却时间到
 *     │  探针成功            ▼
 *     └──────────────── HALF_OPEN
 *          探针失败 ─────────┘（回到 OPEN）
 * </pre>
 *
 * 它解决什么问题：当下游已经挂了，继续把请求打过去只会让情况更糟 ——
 * 你自己的线程被占满、下游被压得更起不来。熔断的作用是【快速失败】，
 * 把资源省下来，也给下游喘息的机会。
 *
 * 面试常被追问的两点，这里都处理了：
 *   1. HALF_OPEN 只能放【一个】探针。如果放开所有请求，那不叫试探，叫雪崩第二波。
 *   2. 失败计数要在成功后清零，否则偶发失败会累积到阈值造成误熔断。
 *
 * 注意：这个实例是【按供应商】创建的（见 ProviderInvoker）。
 * 熔断必须按依赖隔离 —— 否则 A 供应商挂了会把 B 供应商也熔断掉。
 */
public class SimpleCircuitBreaker {

    public enum State {
        /** 正常放行。 */
        CLOSED,
        /** 熔断中，一律快速失败。 */
        OPEN,
        /** 冷却结束，放一个探针试探。 */
        HALF_OPEN
    }

    private final int failureThreshold;
    private final long openDurationMs;

    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private final AtomicBoolean probeInFlight = new AtomicBoolean(false);

    private volatile State state = State.CLOSED;
    private volatile long openedAt = 0L;

    public SimpleCircuitBreaker(int failureThreshold, long openDurationMs) {
        this.failureThreshold = failureThreshold;
        this.openDurationMs = openDurationMs;
    }

    /** 是否放行本次请求。 */
    public synchronized boolean allowRequest() {
        if (state == State.OPEN) {
            if (System.currentTimeMillis() - openedAt >= openDurationMs) {
                state = State.HALF_OPEN;
                probeInFlight.set(true);
                return true;   // 放行这一个探针
            }
            return false;
        }
        if (state == State.HALF_OPEN) {
            // 已经有一个探针在飞，其余请求快速失败 —— 否则就不是"试探"了
            return false;
        }
        return true;
    }

    public synchronized void recordSuccess() {
        consecutiveFailures.set(0);
        probeInFlight.set(false);
        state = State.CLOSED;
    }

    public synchronized void recordFailure() {
        probeInFlight.set(false);
        if (state == State.HALF_OPEN) {
            // 探针失败：立刻回到 OPEN，重新开始冷却
            state = State.OPEN;
            openedAt = System.currentTimeMillis();
            return;
        }
        if (consecutiveFailures.incrementAndGet() >= failureThreshold) {
            state = State.OPEN;
            openedAt = System.currentTimeMillis();
        }
    }

    public synchronized void reset() {
        consecutiveFailures.set(0);
        probeInFlight.set(false);
        state = State.CLOSED;
        openedAt = 0L;
    }

    public State state() {
        return state;
    }

    public int consecutiveFailures() {
        return consecutiveFailures.get();
    }

    public int failureThreshold() {
        return failureThreshold;
    }

    public long openDurationMs() {
        return openDurationMs;
    }
}
