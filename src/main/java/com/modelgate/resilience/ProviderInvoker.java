package com.modelgate.resilience;

import com.modelgate.provider.Provider;
import com.modelgate.provider.ProviderRequest;
import com.modelgate.provider.ProviderResponse;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 容错执行器 —— 把"不确定的上游"包装成"可控的调用"。
 *
 * 六件事，每一件对应一个稳定性模式：
 *   1. 线程池隔离  —— 每个供应商自己的池（bulkhead），一家卡死不会饿死其他家
 *   2. 超时        —— 没有超时，一次上游卡死就能耗尽所有线程
 *   3. 超时后取消  —— 只是"不再等"不够，还得把工作线程真正释放掉
 *   4. 重试 + 退避 —— 只对可重试错误重试，且必须加抖动
 *   5. 熔断        —— 下游已挂就别再打，快速失败省资源
 *   6. 降级        —— 拿不到真结果时给明确兜底，而不是 500
 *
 * ⚠️ 熔断器和线程池都是【按供应商维度】隔离的。
 * 这是本类的关键设计：如果用全局单例，
 *   - A 供应商挂掉会把 B 供应商一起熔断（误伤）
 *   - A 供应商的慢请求会占满线程，把 B 的请求一起饿死（雪崩）
 * 面试问"熔断粒度怎么定"，答案就是这一条。
 */
@Service
public class ProviderInvoker {

    private static final Logger log = LoggerFactory.getLogger(ProviderInvoker.class);

    private final ResilienceProperties props;

    private final Map<String, SimpleCircuitBreaker> breakers = new ConcurrentHashMap<>();
    private final Map<String, ThreadPoolExecutor> pools = new ConcurrentHashMap<>();

    public ProviderInvoker(ResilienceProperties props) {
        this.props = props;
        log.info("容错层初始化: 每供应商线程池={} 队列={} 超时={}ms 最大尝试={} 超时取消={}",
                props.getPoolSize(), props.getQueueCapacity(), props.getTimeoutMs(),
                props.getMaxAttempts(), props.isCancelOnTimeout());
    }

    /**
     * 带容错地调用一个供应商。
     *
     * @param provider 目标供应商
     * @param request  统一请求模型
     * @param options  单次参数覆盖（可为 {@link ResilienceOptions#DEFAULT}）
     */
    public InvocationResult invoke(Provider provider, ProviderRequest request, ResilienceOptions options) {
        long startedAt = System.currentTimeMillis();
        List<String> trace = new ArrayList<>();
        String providerName = provider.name();

        // ---------------------------------------------------------------
        // 熔断器与线程池的粒度是【故意不同】的，这是本类最关键的设计：
        //
        //   熔断器 → 按 (供应商, 模型)
        //     理由：限流、模型下线这类故障往往是【单个模型】的。
        //     如果按供应商熔断，一个模型被限流会导致该供应商下所有模型
        //     全部快速失败 —— M1 实测时就撞上了这个问题。
        //
        //   线程池 → 按 供应商
        //     理由：线程池的目的是限制"对某个上游的总并发"，保护对方也保护自己。
        //     如果按模型建池，"10 个模型 × 每池 4 线程" = 40 并发打到同一家，
        //     隔离就失去意义了。
        //
        // 一句话：熔断关心"这个具体能力能不能用"，隔离关心"别把上游打死"。
        // ---------------------------------------------------------------
        String breakerKey = providerName + "/" + request.model();
        SimpleCircuitBreaker breaker = breakerFor(breakerKey);
        ThreadPoolExecutor pool = poolFor(providerName);

        long timeoutMs = options.timeoutMs() != null
                ? options.timeoutMs()
                : (provider.defaultTimeoutMs() != null ? provider.defaultTimeoutMs() : props.getTimeoutMs());
        int maxAttempts = options.attemptsOrDefault(props.getMaxAttempts());
        boolean cancelOnTimeout = options.cancelOrDefault(props.isCancelOnTimeout());

        // ---- 1. 熔断检查：不该打的时候一个字节都不发 ----
        if (!breaker.allowRequest()) {
            trace.add("供应器 " + breakerKey + " 熔断器处于 " + breaker.state()
                    + "，直接快速失败（未调用上游）");
            return build(false, true, null, fallbackText(),
                    "熔断中，快速失败", "circuit_open", 0, startedAt, breaker, trace);
        }

        String lastError = null;
        String lastErrorType = null;
        int attemptsMade = 0;

        for (int n = 1; n <= maxAttempts; n++) {
            attemptsMade = n;
            try {
                ProviderResponse response = callWithTimeout(provider, request, pool, timeoutMs, cancelOnTimeout);
                breaker.recordSuccess();
                trace.add("第 " + n + " 次尝试：成功（实际模型 " + response.model() + "）");
                return build(true, false, response, response.content(),
                        null, null, n, startedAt, breaker, trace);
            } catch (LlmCallException e) {
                lastError = e.getMessage();
                lastErrorType = e.errorType();
                trace.add("第 " + n + " 次尝试：" + e.getMessage());

                if (!e.isRetryable()) {
                    breaker.recordFailure();
                    trace.add("该错误标记为不可重试，立即放弃（不浪费后续尝试）");
                    break;
                }

                breaker.recordFailure();

                if (n < maxAttempts) {
                    long wait = backoffMs(n);
                    trace.add("退避 " + wait + "ms 后重试");
                    sleepQuietly(wait);
                }
            }
        }

        trace.add("尝试耗尽，返回降级内容");
        return build(false, true, null, fallbackText(),
                lastError, lastErrorType, attemptsMade, startedAt, breaker, trace);
    }

    /**
     * 在供应商专属线程池里执行一次调用，并在超时后取消。
     */
    private ProviderResponse callWithTimeout(Provider provider, ProviderRequest request,
                                             ThreadPoolExecutor pool, long timeoutMs,
                                             boolean cancelOnTimeout) throws LlmCallException {
        Future<ProviderResponse> future;
        try {
            Callable<ProviderResponse> task = () -> provider.chat(request);
            future = pool.submit(task);
        } catch (RejectedExecutionException e) {
            // 隔离在这里生效：主线程（Tomcat 工作线程）没被拖住，
            // 而是立刻拿到明确失败，可以马上去做降级处理。
            throw new LlmCallException(
                    "供应商 " + provider.name() + " 的线程池与队列均已满，请求被拒绝（隔离生效，主线程未被拖住）",
                    true, "bulkhead_rejected");
        }

        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            // 关键区别：cancel(true) 会中断工作线程，让它真正退出。
            // 只"不再等待"而不取消，工作线程会一直占着池子里的位置，
            // 挂起几次后池子被永久占满，服务"静默死亡"——不抛异常、不记日志、
            // 健康检查还是 UP。Phase 0 实验 6 有完整的实测对照。
            future.cancel(cancelOnTimeout);
            String note = cancelOnTimeout ? "（已中断工作线程，线程会被释放）" : "（未中断！工作线程仍被占用）";
            throw new LlmCallException("调用超时 >" + timeoutMs + "ms " + note, true, "timeout");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof LlmCallException lce) {
                throw lce;
            }
            throw new LlmCallException("调用抛出未预期异常：" + cause, true, "unexpected");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmCallException("等待被中断", false, "interrupted");
        }
    }

    /**
     * 指数退避 + 抖动。
     *
     * 抖动不是可选项：如果所有客户端按同样的 1s/2s/4s 退避，
     * 它们会在同一时刻集体重试，把刚恢复的下游再打挂一次 —— 惊群效应。
     */
    private long backoffMs(int attempt) {
        long base = props.getBackoffBaseMs() * (1L << (attempt - 1));
        long jitter = ThreadLocalRandom.current().nextLong(0, Math.max(1, base / 2));
        return base + jitter;
    }

    private String fallbackText() {
        return "【降级响应】上游模型暂时不可用，这是兜底内容，不是模型产出。请稍后重试。";
    }

    private void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private InvocationResult build(boolean success, boolean degraded, ProviderResponse response,
                                   String content, String error, String errorType, int attempts,
                                   long startedAt, SimpleCircuitBreaker breaker, List<String> trace) {
        return new InvocationResult(success, degraded, response, content, error, errorType,
                attempts, System.currentTimeMillis() - startedAt, breaker.state().name(), trace);
    }

    private SimpleCircuitBreaker breakerFor(String breakerKey) {
        return breakers.computeIfAbsent(breakerKey, k -> new SimpleCircuitBreaker(
                props.getBreakerFailureThreshold(), props.getBreakerOpenMs()));
    }

    private ThreadPoolExecutor poolFor(String providerName) {
        return pools.computeIfAbsent(providerName, n -> {
            ThreadPoolExecutor executor = new ThreadPoolExecutor(
                    props.getPoolSize(), props.getPoolSize(),
                    60L, TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(props.getQueueCapacity()),
                    r -> {
                        Thread t = new Thread(r, "upstream-" + n);
                        t.setDaemon(true);
                        return t;
                    },
                    // 有界队列 + 快速拒绝：无界队列会吃光内存，
                    // 并把问题推迟到彻底崩溃那一刻才暴露
                    new ThreadPoolExecutor.AbortPolicy());
            executor.allowCoreThreadTimeOut(true);
            return executor;
        });
    }

    // ------------------------------------------------------------------ 观测与实验用

    /**
     * 熔断器与线程池的实时状态。
     *
     * 返回结构刻意分成两块，因为两者的粒度不同（见 invoke 里的注释）：
     *   breakers: "供应商/模型" -> 状态
     *   pools:    "供应商"      -> 状态
     */
    public Map<String, Object> stats() {
        Map<String, Object> breakersView = new LinkedHashMap<>();
        breakers.forEach((key, breaker) -> {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("state", breaker.state().name());
            view.put("consecutiveFailures", breaker.consecutiveFailures());
            view.put("failureThreshold", breaker.failureThreshold());
            breakersView.put(key, view);
        });

        Map<String, Object> poolsView = new LinkedHashMap<>();
        pools.forEach((name, pool) -> {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("activeThreads", pool.getActiveCount());
            view.put("poolSize", pool.getPoolSize());
            view.put("queueSize", pool.getQueue().size());
            view.put("queueCapacity", props.getQueueCapacity());
            view.put("completedTasks", pool.getCompletedTaskCount());
            poolsView.put(name, view);
        });

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("breakers", breakersView);
        result.put("pools", poolsView);
        return result;
    }

    /**
     * 重置熔断器。
     *
     * @param prefix 供应商名（重置它下面所有模型的熔断器）、
     *               "供应商/模型"（只重置这一个）、或 null（全部重置）
     */
    public void resetBreakers(String prefix) {
        if (prefix == null) {
            breakers.values().forEach(SimpleCircuitBreaker::reset);
            log.info("已重置全部熔断器");
            return;
        }
        String withSlash = prefix + "/";
        int count = 0;
        for (Map.Entry<String, SimpleCircuitBreaker> e : breakers.entrySet()) {
            if (e.getKey().equals(prefix) || e.getKey().startsWith(withSlash)) {
                e.getValue().reset();
                count++;
            }
        }
        log.info("已重置 {} 个熔断器（匹配前缀 {}）", count, prefix);
    }

    public long configuredTimeoutMs() {
        return props.getTimeoutMs();
    }

    public int configuredMaxAttempts() {
        return props.getMaxAttempts();
    }

    public boolean configuredCancelOnTimeout() {
        return props.isCancelOnTimeout();
    }

    @PreDestroy
    public void shutdown() {
        pools.values().forEach(ThreadPoolExecutor::shutdownNow);
    }
}
