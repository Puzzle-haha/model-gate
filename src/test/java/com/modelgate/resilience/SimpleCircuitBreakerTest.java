package com.modelgate.resilience;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 熔断状态机测试。
 *
 * 为什么这个类值得单测：状态机的 bug 是**静默的**。
 * 写错了不会抛异常，只会在生产环境表现为"服务莫名其妙开始拒绝所有请求"
 * 或者"下游已经恢复了但熔断一直不放开"。手工测试基本发现不了。
 *
 * 三个关键行为：
 *   1. 连续失败到阈值才熔断（偶发失败不该熔断）
 *   2. HALF_OPEN 只放一个探针（放开所有请求就不是试探，是雪崩第二波）
 *   3. 成功后失败计数清零（否则偶发失败会累积成误熔断）
 */
class SimpleCircuitBreakerTest {

    private static final int THRESHOLD = 3;
    private static final long OPEN_MS = 80;

    private SimpleCircuitBreaker newBreaker() {
        return new SimpleCircuitBreaker(THRESHOLD, OPEN_MS);
    }

    @Test
    @DisplayName("初始状态为 CLOSED，且放行请求")
    void startsClosedAndAllows() {
        SimpleCircuitBreaker breaker = newBreaker();

        assertThat(breaker.state()).isEqualTo(SimpleCircuitBreaker.State.CLOSED);
        assertThat(breaker.allowRequest()).isTrue();
        assertThat(breaker.consecutiveFailures()).isZero();
    }

    @Test
    @DisplayName("失败次数未达阈值时不熔断")
    void staysClosedBelowThreshold() {
        SimpleCircuitBreaker breaker = newBreaker();

        breaker.recordFailure();
        breaker.recordFailure();

        assertThat(breaker.state()).isEqualTo(SimpleCircuitBreaker.State.CLOSED);
        assertThat(breaker.allowRequest()).isTrue();
    }

    @Test
    @DisplayName("连续失败达到阈值后熔断，并拒绝请求")
    void opensAtThreshold() {
        SimpleCircuitBreaker breaker = newBreaker();

        for (int i = 0; i < THRESHOLD; i++) {
            breaker.recordFailure();
        }

        assertThat(breaker.state()).isEqualTo(SimpleCircuitBreaker.State.OPEN);
        assertThat(breaker.allowRequest()).isFalse();
    }

    @Test
    @DisplayName("成功会清零失败计数 —— 否则偶发失败会累积成误熔断")
    void successResetsFailureCount() {
        SimpleCircuitBreaker breaker = newBreaker();

        breaker.recordFailure();
        breaker.recordFailure();
        breaker.recordSuccess();
        assertThat(breaker.consecutiveFailures()).isZero();

        // 再来两次仍然不该熔断（如果计数没清零，这里就变成 4 次，超过阈值了）
        breaker.recordFailure();
        breaker.recordFailure();
        assertThat(breaker.state()).isEqualTo(SimpleCircuitBreaker.State.CLOSED);
    }

    @Test
    @DisplayName("冷却时间到后进入 HALF_OPEN，且只放行一个探针")
    void halfOpenAllowsExactlyOneProbe() throws InterruptedException {
        SimpleCircuitBreaker breaker = newBreaker();
        for (int i = 0; i < THRESHOLD; i++) {
            breaker.recordFailure();
        }
        assertThat(breaker.allowRequest()).isFalse();

        Thread.sleep(OPEN_MS + 30);

        // 第一个请求拿到探针资格
        assertThat(breaker.allowRequest()).isTrue();
        assertThat(breaker.state()).isEqualTo(SimpleCircuitBreaker.State.HALF_OPEN);

        // 后续请求必须被拒 —— 如果这里放行，就不是"试探"而是让所有请求
        // 一起去打还没恢复的下游，等于雪崩第二波
        assertThat(breaker.allowRequest()).isFalse();
        assertThat(breaker.allowRequest()).isFalse();
    }

    @Test
    @DisplayName("探针成功则恢复到 CLOSED")
    void probeSuccessClosesCircuit() throws InterruptedException {
        SimpleCircuitBreaker breaker = newBreaker();
        for (int i = 0; i < THRESHOLD; i++) {
            breaker.recordFailure();
        }
        Thread.sleep(OPEN_MS + 30);

        assertThat(breaker.allowRequest()).isTrue();   // 拿到探针
        breaker.recordSuccess();

        assertThat(breaker.state()).isEqualTo(SimpleCircuitBreaker.State.CLOSED);
        assertThat(breaker.allowRequest()).isTrue();
    }

    @Test
    @DisplayName("探针失败则立刻回到 OPEN，并重新开始冷却")
    void probeFailureReopensCircuit() throws InterruptedException {
        SimpleCircuitBreaker breaker = newBreaker();
        for (int i = 0; i < THRESHOLD; i++) {
            breaker.recordFailure();
        }
        Thread.sleep(OPEN_MS + 30);

        assertThat(breaker.allowRequest()).isTrue();   // 拿到探针
        breaker.recordFailure();

        assertThat(breaker.state()).isEqualTo(SimpleCircuitBreaker.State.OPEN);
        // 冷却时间重新开始，所以此刻不放行
        assertThat(breaker.allowRequest()).isFalse();
    }

    @Test
    @DisplayName("reset 后完全恢复初始状态")
    void resetRestoresInitialState() {
        SimpleCircuitBreaker breaker = newBreaker();
        for (int i = 0; i < THRESHOLD; i++) {
            breaker.recordFailure();
        }

        breaker.reset();

        assertThat(breaker.state()).isEqualTo(SimpleCircuitBreaker.State.CLOSED);
        assertThat(breaker.consecutiveFailures()).isZero();
        assertThat(breaker.allowRequest()).isTrue();
    }
}
