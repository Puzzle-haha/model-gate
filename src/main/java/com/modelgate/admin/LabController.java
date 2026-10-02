package com.modelgate.admin;

import com.modelgate.cache.ResponseCache;
import com.modelgate.provider.ChatMessage;
import com.modelgate.provider.FaultMode;
import com.modelgate.provider.Provider;
import com.modelgate.provider.ProviderFactory;
import com.modelgate.provider.ProviderRegistry;
import com.modelgate.provider.ProviderRequest;
import com.modelgate.ratelimit.RateLimitGuard;
import com.modelgate.ratelimit.RateLimitProperties;
import com.modelgate.resilience.InvocationResult;
import com.modelgate.resilience.ProviderInvoker;
import com.modelgate.resilience.ResilienceOptions;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 运维与实验台接口（内部用，不属于对外 API）。
 *
 * 它存在的意义：把"上游不确定性"变成可以按按钮复现的东西。
 * 你不需要凭运气等故障发生，点一下就能让上游超时、返回 5xx 或空内容。
 *
 * 注意这个包（com.modelgate.admin）不在 OpenAI advice 的作用域内，
 * 所以它的错误响应走全局的 {timestamp,status,error,message} 结构 ——
 * 正好可以用来验证"两套错误契约并存"是否按预期工作。
 */
@RestController
@RequestMapping("/api/lab")
public class LabController {

    private final ProviderRegistry registry;
    private final ProviderInvoker invoker;
    private final ProviderFactory factory;
    private final RateLimitGuard guard;
    private final RateLimitProperties rateLimitProps;
    private final ResponseCache responseCache;

    public LabController(ProviderRegistry registry, ProviderInvoker invoker, ProviderFactory factory,
                         RateLimitGuard guard, RateLimitProperties rateLimitProps,
                         ResponseCache responseCache) {
        this.registry = registry;
        this.invoker = invoker;
        this.factory = factory;
        this.guard = guard;
        this.rateLimitProps = rateLimitProps;
        this.responseCache = responseCache;
    }

    /** 可注入的故障模式清单。 */
    @GetMapping("/modes")
    public List<Map<String, Object>> modes() {
        return Arrays.stream(FaultMode.values()).map(m -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("mode", m.name());
            item.put("model", "mock-" + m.name().toLowerCase());
            item.put("description", m.description());
            item.put("retryable", m.retryable());
            return item;
        }).toList();
    }

    /** 供应商清单。 */
    @GetMapping("/providers")
    public List<Map<String, Object>> providers() {
        return registry.inventory();
    }

    /**
     * 给定模型，看会按什么顺序尝试哪些供应商。
     *
     * 排查"请求为什么打到那家去了"最直接的接口。
     */
    @GetMapping("/routing")
    public Map<String, Object> routing(@RequestParam String model) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("model", model);
        result.put("candidates", registry.routeFor(model));
        return result;
    }

    /**
     * 各供应商的密钥池状态（密钥已脱敏）。
     *
     * 用来回答"为什么这家一直失败""密钥是不是都在冷却中"。
     */
    @GetMapping("/keys")
    public List<Map<String, Object>> keys() {
        return factory.keyPoolStats();
    }

    // ------------------------------------------------------------------ 限流与配额

    /** 限流配置（用于确认实际生效的参数）。 */
    @GetMapping("/ratelimit")
    public Map<String, Object> rateLimitConfig() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", rateLimitProps.isEnabled());
        m.put("capacity", rateLimitProps.getCapacity());
        m.put("refillPerSecond", rateLimitProps.getRefillPerSecond());
        m.put("failOpen", rateLimitProps.isFailOpen());
        m.put("quotaEnabled", rateLimitProps.isQuotaEnabled());
        m.put("dailyTokens", rateLimitProps.getDailyTokens());
        m.put("monthlyTokens", rateLimitProps.getMonthlyTokens());
        return m;
    }

    /**
     * 查询某调用方的配额用量。
     *
     * 调用方凭证走【请求头】而不是查询参数：URL 会进 access log、
     * 浏览器历史、Referer 头，把密钥放进去等于到处散播。
     * 这是个很容易被忽略的安全细节。
     */
    @GetMapping("/quota")
    public Map<String, Object> quota(@RequestHeader(value = "X-API-Key", required = false) String apiKey) {
        String tenant = guard.tenantKey(apiKey);
        Map<String, Object> m = new LinkedHashMap<>(guard.quotaService().usage(tenant));
        m.put("tenantHash", tenant);
        return m;
    }

    /** 重置某调用方的令牌桶和配额（测试用）。 */
    @PostMapping("/ratelimit/reset")
    public Map<String, Object> resetRateLimit(
            @RequestHeader(value = "X-API-Key", required = false) String apiKey) {
        String tenant = guard.tenantKey(apiKey);
        guard.limiter().reset(tenant);
        guard.quotaService().reset(tenant);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("reset", true);
        m.put("tenantHash", tenant);
        return m;
    }

    // ------------------------------------------------------------------ 缓存

    /** 缓存运行状态：命中率、单飞合并次数、错误数。 */
    @GetMapping("/cache")
    public Map<String, Object> cacheStats() {
        return responseCache.stats();
    }

    /** 清零缓存统计（不影响已缓存的数据，只重置计数）。 */
    @PostMapping("/cache/reset-stats")
    public Map<String, Object> resetCacheStats() {
        responseCache.resetStats();
        return responseCache.stats();
    }

    /** 各供应商的熔断器与线程池实时状态。 */
    @GetMapping("/stats")
    public Map<String, Object> stats() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("providers", invoker.stats());

        // 字段名刻意叫 globalConfig 而不是 config。
        //
        // 这里报告的是【全局默认值】，不反映单次请求的覆盖参数。
        // 之前叫 config，做隔离实验时会出现很迷惑的现象：
        // 明明传了 cancelOnTimeout=false，返回的 config 却显示 true ——
        // 因为读的是全局默认，不是本次取值。字段名和内容对不上，
        // 会让做实验的人怀疑自己的操作，而不是怀疑这个字段。
        Map<String, Object> globalConfig = new LinkedHashMap<>();
        globalConfig.put("timeoutMs", invoker.configuredTimeoutMs());
        globalConfig.put("maxAttempts", invoker.configuredMaxAttempts());
        globalConfig.put("cancelOnTimeout", invoker.configuredCancelOnTimeout());
        result.put("globalConfig", globalConfig);
        return result;
    }

    /** 重置熔断器。带 provider 参数则只重置那一家。 */
    @PostMapping("/breakers/reset")
    public Map<String, Object> resetBreakers(@RequestParam(required = false) String provider) {
        invoker.resetBreakers(provider);
        return stats();
    }

    /**
     * 单次调用，可覆盖容错参数 —— 用于逐项对照实验。
     */
    @PostMapping("/ask")
    public Map<String, Object> ask(@RequestParam(defaultValue = "mock-ok") String model,
                                   @RequestParam(defaultValue = "你好") String prompt,
                                   @RequestParam(required = false) Long timeoutMs,
                                   @RequestParam(required = false) Integer maxAttempts,
                                   @RequestParam(required = false) Boolean cancelOnTimeout) {

        Provider provider = registry.resolve(model);
        ProviderRequest request = new ProviderRequest(
                model, List.of(new ChatMessage("user", prompt)), null, null);

        InvocationResult result = invoker.invoke(provider, request,
                new ResilienceOptions(timeoutMs, maxAttempts, cancelOnTimeout));

        Map<String, Object> view = new LinkedHashMap<>();
        view.put("provider", provider.name());
        view.put("model", model);
        view.put("success", result.success());
        view.put("degraded", result.degraded());
        view.put("content", result.content());
        view.put("error", result.error());
        view.put("errorType", result.errorType());
        view.put("attempts", result.attempts());
        view.put("elapsedMs", result.elapsedMs());
        view.put("breakerState", result.breakerState());
        view.put("trace", result.trace());
        return view;
    }

    /**
     * 并发压测：同时打 count 个请求，观察隔离机制的效果。
     *
     * 用 mock-hang + count > 池容量，你会看到：
     *   - 一部分请求被快速拒绝（这是保护，不是故障）
     *   - 另一部分等到超时后降级
     *   - 但接口【始终能响应】，不会把 Tomcat 的线程也拖死
     *
     * 再把 cancelOnTimeout 设成 false 跑一遍，对比线程池状态 —— 差别非常明显。
     */
    @PostMapping("/load")
    public Map<String, Object> load(@RequestParam(defaultValue = "mock-hang") String model,
                                    @RequestParam(defaultValue = "20") int count,
                                    @RequestParam(required = false) Long timeoutMs,
                                    @RequestParam(required = false) Integer maxAttempts,
                                    @RequestParam(required = false) Boolean cancelOnTimeout) throws InterruptedException {

        Provider provider = registry.resolve(model);
        ResilienceOptions options = new ResilienceOptions(timeoutMs, maxAttempts, cancelOnTimeout);
        int n = Math.min(count, 200);

        long t0 = System.currentTimeMillis();
        ExecutorService callers = Executors.newFixedThreadPool(n, r -> {
            Thread t = new Thread(r, "load-caller");
            t.setDaemon(true);
            return t;
        });

        List<Future<InvocationResult>> futures = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            final int idx = i;
            futures.add(callers.submit(() -> invoker.invoke(provider,
                    new ProviderRequest(model, List.of(new ChatMessage("user", "压测请求 #" + idx)), null, null),
                    options)));
        }
        callers.shutdown();

        int success = 0, degraded = 0, rejected = 0, circuitOpen = 0, taskFailed = 0;
        long maxSingle = 0;
        for (Future<InvocationResult> f : futures) {
            try {
                InvocationResult r = f.get(180, TimeUnit.SECONDS);
                if (r.success()) {
                    success++;
                } else {
                    degraded++;
                }
                if (r.trace().stream().anyMatch(s -> s.contains("被拒绝"))) {
                    rejected++;
                }
                if ("circuit_open".equals(r.errorType())) {
                    circuitOpen++;
                }
                maxSingle = Math.max(maxSingle, r.elapsedMs());
            } catch (Exception e) {
                taskFailed++;
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("model", model);
        out.put("count", n);
        out.put("success", success);
        out.put("degraded", degraded);
        out.put("hitBulkheadRejection", rejected);
        out.put("hitCircuitOpen", circuitOpen);
        out.put("taskFailed", taskFailed);
        out.put("wallMs", System.currentTimeMillis() - t0);
        out.put("maxSingleElapsedMs", maxSingle);

        // 本次请求实际传入的覆盖参数。null = 未指定，会回落到 globalConfig。
        // 必须和 statsAfter.globalConfig 一起看才有意义 ——
        // 只看 globalConfig 会误以为覆盖参数没生效。
        Map<String, Object> requested = new LinkedHashMap<>();
        requested.put("timeoutMs", timeoutMs);
        requested.put("maxAttempts", maxAttempts);
        requested.put("cancelOnTimeout", cancelOnTimeout);
        out.put("optionsRequested", requested);

        out.put("statsAfter", stats());
        return out;
    }
}
