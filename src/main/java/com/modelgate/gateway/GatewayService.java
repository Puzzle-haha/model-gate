package com.modelgate.gateway;

import com.modelgate.calllog.CallLog;
import com.modelgate.calllog.CallLogWriter;
import com.modelgate.cache.CacheProperties;
import com.modelgate.cache.CachedResponse;
import com.modelgate.cache.ResponseCache;
import com.modelgate.observability.GatewayMetrics;
import com.modelgate.pricing.CostCalculator;
import com.modelgate.provider.ChatMessage;
import com.modelgate.provider.Provider;
import com.modelgate.provider.ProviderRegistry;
import com.modelgate.provider.ProviderRequest;
import com.modelgate.ratelimit.RateLimitGuard;
import com.modelgate.resilience.InvocationResult;
import com.modelgate.resilience.ProviderInvoker;
import com.modelgate.resilience.ResilienceOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 网关编排层。一次客户端请求的完整旅程：
 *
 *   准入检查（限流/配额）
 *     → 模型解析
 *     → 缓存查找（命中则直接返回，成本 0）
 *     → 单飞抢加载权（防缓存击穿）
 *     → 候选供应商逐个尝试（含故障转移）
 *     → 回填缓存
 *     → 落库（token 用量 + 成本）
 *
 * 顺序是有讲究的，每一层都在为后面的层"挡掉"不该发生的开销：
 *   限流最先 → 挡住过量请求
 *   缓存其次 → 挡住重复请求
 *   路由最后 → 只处理真正需要上游的请求
 */
@Service
public class GatewayService {

    private static final Logger log = LoggerFactory.getLogger(GatewayService.class);

    /**
     * 「请求本身有问题」的错误类型 —— 换供应商也没用，应当立刻停止故障转移。
     *
     * 401（这家密钥坏了）→ 换一家可能成功 ✅ 应该转移
     * 400（请求格式就是错的）→ 换十家都是 400 ❌ 不该转移
     */
    private static final Set<String> REQUEST_LEVEL_ERRORS = Set.of(
            "upstream_400", "invalid_request", "unsupported_feature"
    );

    private final ProviderRegistry registry;
    private final ProviderInvoker invoker;
    private final GatewayProperties props;
    private final CallLogWriter callLogWriter;
    private final RateLimitGuard guard;
    private final ResponseCache cache;
    private final CacheProperties cacheProps;
    private final CostCalculator costCalculator;
    private final GatewayMetrics metrics;

    public GatewayService(ProviderRegistry registry, ProviderInvoker invoker,
                          GatewayProperties props, CallLogWriter callLogWriter,
                          RateLimitGuard guard, ResponseCache cache,
                          CacheProperties cacheProps, CostCalculator costCalculator,
                          GatewayMetrics metrics) {
        this.registry = registry;
        this.invoker = invoker;
        this.props = props;
        this.callLogWriter = callLogWriter;
        this.guard = guard;
        this.cache = cache;
        this.cacheProps = cacheProps;
        this.costCalculator = costCalculator;
        this.metrics = metrics;
        log.info("网关初始化: 默认模型={} 故障转移深度={}（最多上游调用次数 = 深度 × 重试次数）",
                props.getDefaultModel(), props.getFailoverDepth());
    }

    public GatewayResult chat(String requestedModel,
                              List<ChatMessage> messages,
                              Double temperature,
                              Integer maxTokens,
                              String apiKey) {
        return chat(requestedModel, messages, temperature, maxTokens, apiKey, false);
    }

    /**
     * 评测专用入口。
     *
     * 与普通请求有两点不同，都是刻意的：
     *
     *   1. **绕过缓存**。评测要测的是模型本身的能力，命中缓存等于没测模型。
     *      同一批任务跑两次，第二次全是缓存命中，准确率会变成假的 100%。
     *      这是评测里最容易犯、也最隐蔽的错误。
     *
     *   2. **绕过限流与配额**。评测是内部批量操作，几十上百次调用会立刻
     *      撞上为外部租户设的限流。但容错层（重试/熔断）保留 ——
     *      评测也应该反映真实的容错行为。
     */
    public GatewayResult chatForEval(String requestedModel,
                                     List<ChatMessage> messages,
                                     Double temperature,
                                     Integer maxTokens) {
        return chat(requestedModel, messages, temperature, maxTokens, "__eval__", true);
    }

    private GatewayResult chat(String requestedModel,
                               List<ChatMessage> messages,
                               Double temperature,
                               Integer maxTokens,
                               String apiKey,
                               boolean evalMode) {

        String requestId = "mg-" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);

        // ==================================================================
        // 1. 准入检查必须在最前面，且在任何上游调用之前。
        //    放在后面就失去意义了：那时配额已经花掉、上游已经被打了，
        //    "拒绝"只是事后通知，省不下任何成本。
        // ==================================================================
        String tenant = evalMode ? "eval" : guard.checkOrThrow(apiKey);

        // 2. 模型解析。auto → 配置的默认模型。
        //    「按成本/质量策略选模型」要等 M6 评测有数据之后才能做 ——
        //    没有实测数据支撑的"智能路由"只是拍脑袋。
        String model = (requestedModel == null || requestedModel.isBlank() || "auto".equalsIgnoreCase(requestedModel))
                ? props.getDefaultModel()
                : requestedModel;

        ProviderRequest providerRequest = new ProviderRequest(model, messages, temperature, maxTokens);
        long startedAt = System.currentTimeMillis();
        List<String> failoverTrace = new ArrayList<>();

        // ==================================================================
        // 3. 缓存查找（评测模式直接跳过，见 chatForEval 的说明）
        // ==================================================================
        boolean cacheable = !evalMode && cache.isCacheable(providerRequest);
        String cacheKey = cacheable ? cache.keyFor(providerRequest, tenant) : null;

        if (cacheable) {
            CachedResponse hit = cache.get(cacheKey);
            if (hit != null) {
                metrics.recordCache("hit");
                return fromCache(requestId, tenant, requestedModel, model, hit,
                        startedAt, failoverTrace);
            }
        }

        // ==================================================================
        // 4. 单飞：只让一个请求去加载，其余等它的结果
        //
        //    没有这一步的话，热点键过期的瞬间，N 个并发请求会一起去打上游 ——
        //    缓存不但没保护上游，反而制造了尖峰。
        // ==================================================================
        boolean loader = true;
        if (cacheable) {
            loader = cache.tryBeginLoad(cacheKey);
            if (!loader) {
                CachedResponse waited = cache.awaitLoad(cacheKey, cacheProps.getLoadWaitMs());
                if (waited != null) {
                    // 被单飞合并 —— 单独统计，因为它和"命中已有缓存"是两回事：
                    // 前者说明有并发争抢，后者说明缓存工作正常。
                    // 混在一起就分不清"缓存命中率高"是因为缓存有效，
                    // 还是因为大量并发被合并了。
                    metrics.recordCache("coalesced");
                    return fromCache(requestId, tenant, requestedModel, model, waited,
                            startedAt, failoverTrace);
                }
                // 等超时了：自己也去加载，绝不无限等待。
                // 代价是这一刻可能有重复上游调用 —— 用少量重复换"不会卡死"，
                // 这个取舍是划算的。
                failoverTrace.add("等待其他请求加载缓存超时，自行加载");
            }
        }
        if (cacheable) {
            metrics.recordCache("miss");
        }

        // ==================================================================
        // 5. 走供应商（含故障转移）
        // ==================================================================
        CachedResponse toCache = null;
        try {
            GatewayResult result = callProviders(requestId, tenant, requestedModel, model,
                    providerRequest, startedAt, failoverTrace);

            if (cacheable && result.invocation().success()) {
                toCache = CachedResponse.from(result.invocation().response(), model);
                cache.put(cacheKey, toCache);
            }
            return result;
        } finally {
            // 无论成功、失败还是抛异常，都必须唤醒等待者。
            // 漏掉这一步，其他请求会一直等到超时 —— 虽然 awaitLoad 有超时兜底，
            // 但那意味着白白浪费几秒钟。finishLoad 是幂等的，重复调用无副作用。
            if (cacheable && loader) {
                cache.finishLoad(cacheKey, toCache);
            }
        }
    }

    /** 从缓存命中构造结果。 */
    private GatewayResult fromCache(String requestId, String tenant, String requestedModel,
                                    String model, CachedResponse cached,
                                    long startedAt, List<String> failoverTrace) {
        ProviderResponseView view = new ProviderResponseView(cached);

        failoverTrace.add("命中响应缓存，未调用上游");

        // 缓存命中也要落一条日志 —— 否则"缓存到底省了多少"就没有数据支撑。
        // 但成本记 0（没有真实调用），也不计入配额（配额是用来防成本的）。
        callLogWriter.submit(CallLog.record(
                requestId, tenant, requestedModel, "cache", model,
                true, null, null, 0,
                cached.promptTokens(), cached.completionTokens(),
                view.totalTokens(), System.currentTimeMillis() - startedAt,
                "N/A", true, 0L));

        InvocationResult invocation = new InvocationResult(
                true, false, cached.toProviderResponse(), cached.content(),
                null, null, 0, System.currentTimeMillis() - startedAt, "N/A", failoverTrace);

        return new GatewayResult(requestId, "cache", model, invocation,
                System.currentTimeMillis() - startedAt, failoverTrace, 0);
    }

    /** 走候选供应商，含故障转移、日志与成本核算。 */
    private GatewayResult callProviders(String requestId, String tenant, String requestedModel,
                                        String model, ProviderRequest providerRequest,
                                        long startedAt, List<String> failoverTrace) {
        List<Provider> candidates = registry.candidates(model);
        int depth = Math.min(candidates.size(), Math.max(1, props.getFailoverDepth()));

        InvocationResult lastResult = null;
        Provider lastProvider = candidates.get(0);
        int tried = 0;

        for (int i = 0; i < depth; i++) {
            Provider provider = candidates.get(i);
            lastProvider = provider;
            tried = i + 1;

            InvocationResult result = invoker.invoke(provider, providerRequest, ResilienceOptions.DEFAULT);
            lastResult = result;

            // 先算成本，指标和落库共用同一个值 —— 两处各算一遍迟早会算出不一样的数
            long costMicros = computeCost(model, result);
            recordMetrics(provider.name(), model, result, costMicros);

            // 每一次供应商尝试都落一条日志（同一个 requestId）。
            // 这样"发生了多少次故障转移"就能直接从数据里查出来：
            //   SELECT request_id FROM call_log GROUP BY request_id HAVING COUNT(DISTINCT provider) > 1
            persistCallLog(requestId, tenant, requestedModel, provider.name(), model, result, costMicros);

            if (result.success()) {
                if (i > 0) {
                    failoverTrace.add("第 " + tried + " 个候选供应商 " + provider.name() + " 成功");
                }
                // 用真实 token 用量记账（拿不到就记 0，不做假账）。
                // 失败/降级的调用不记配额 —— 上游没产出 token。
                Integer usedTokens = result.response() == null ? null : result.response().totalTokens();
                guard.recordUsage(tenant, usedTokens);

                log.debug("requestId={} provider={} model={} success=true 尝试供应商数={} 耗时={}ms",
                        requestId, provider.name(), model, tried, result.elapsedMs());
                return new GatewayResult(requestId, provider.name(), model, result,
                        System.currentTimeMillis() - startedAt, failoverTrace, tried);
            }

            failoverTrace.add("候选 " + tried + " [" + provider.name() + "] 失败("
                    + result.errorType() + "): " + abbreviate(result.error()));

            if (REQUEST_LEVEL_ERRORS.contains(result.errorType())) {
                failoverTrace.add("该错误属于请求本身有问题，换供应商也不会成功，停止故障转移");
                break;
            }
            if (i < depth - 1) {
                failoverTrace.add("故障转移到下一个候选供应商");
            }
        }

        failoverTrace.add("所有候选供应商均失败（共尝试 " + tried + " 个），返回降级内容");
        log.warn("requestId={} model={} 全部候选失败，尝试供应商数={}", requestId, model, tried);

        return new GatewayResult(requestId, lastProvider.name(), model, lastResult,
                System.currentTimeMillis() - startedAt, failoverTrace, tried);
    }

    /**
     * 计算这次调用的成本。
     *
     * 只有真的调用了上游并成功才计费 —— 降级返回的兜底文案不产生费用，
     * 失败的上游调用也不该计（我们没拿到产出）。
     */
    private long computeCost(String model, InvocationResult result) {
        if (!result.success() || result.response() == null) {
            return 0L;
        }
        return costCalculator.costMicros(model,
                result.response().promptTokens(), result.response().completionTokens());
    }

    /** 记录 Micrometer 指标。标签只用取值有限的维度，避免基数爆炸。 */
    private void recordMetrics(String providerName, String model,
                               InvocationResult result, long costMicros) {
        metrics.recordCall(providerName, model,
                result.success() ? "success" : "degraded",
                result.elapsedMs(), costMicros);
        if ("circuit_open".equals(result.errorType())) {
            metrics.recordCircuitOpen(providerName, model);
        }
    }

    /**
     * 异步落库。CallLogWriter.submit 承诺永不抛异常、永不阻塞 ——
     * 所以这里不需要 try-catch。"记录日志失败导致业务失败"是绝对不能接受的。
     */
    private void persistCallLog(String requestId, String tenant, String requestedModel,
                                String providerName, String model, InvocationResult result,
                                long costMicros) {
        Integer promptTokens = null;
        Integer completionTokens = null;
        Integer totalTokens = null;

        if (result.response() != null) {
            promptTokens = result.response().promptTokens();
            completionTokens = result.response().completionTokens();
            totalTokens = result.response().totalTokens();
        }

        callLogWriter.submit(CallLog.record(
                requestId, tenant, requestedModel, providerName, model,
                result.success(), result.errorType(), result.error(),
                result.attempts(), promptTokens, completionTokens, totalTokens,
                result.elapsedMs(), result.breakerState(),
                false, costMicros));
    }

    private String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 80 ? s : s.substring(0, 80) + "…";
    }

    /** 小的辅助记录，避免在 fromCache 里重复算 totalTokens。 */
    private record ProviderResponseView(CachedResponse cached) {
        Integer totalTokens() {
            Integer p = cached.promptTokens();
            Integer c = cached.completionTokens();
            if (p == null && c == null) {
                return null;
            }
            return (p == null ? 0 : p) + (c == null ? 0 : c);
        }
    }

    /**
     * 一次网关调用的结果。
     *
     * @param failoverTrace  供应商切换轨迹 + 缓存决策轨迹，出问题一眼看出发生了什么
     * @param providersTried 实际尝试了几个供应商（缓存命中为 0）
     */
    public record GatewayResult(String requestId, String provider, String model,
                                InvocationResult invocation, long totalMs,
                                List<String> failoverTrace, int providersTried) {
    }
}
