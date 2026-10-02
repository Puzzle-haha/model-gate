package com.modelgate.admin;

import com.modelgate.calllog.CallLogRepository;
import com.modelgate.cache.ResponseCache;
import com.modelgate.pricing.CostCalculator;
import com.modelgate.ratelimit.RateLimitProperties;
import com.modelgate.resilience.ProviderInvoker;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Dashboard 的数据源。
 *
 * 设计成一个接口返回全部数据，而不是让前端调五六个接口：
 *   - 前端一次请求拿齐，避免"加载到一半"的中间态
 *   - 每个子接口都会各自查一次数据库，合成一个接口能少几次往返
 *
 * 数据分两类，来源不同：
 *   - **窗口内统计**（调用量、百分位、成本）来自 MySQL —— 有历史，可回溯
 *   - **实时状态**（熔断器、线程池、缓存计数）来自内存 —— 只有"自启动以来"
 *
 * 这个区分很重要：进程重启后内存指标归零，但数据库里的历史还在。
 * 面试被问"你的监控能看多久的历史"，答案就在这里。
 */
@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final CallLogRepository repository;
    private final ProviderInvoker invoker;
    private final ResponseCache cache;
    private final RateLimitProperties rateLimitProps;
    private final CostCalculator costCalculator;

    public DashboardController(CallLogRepository repository, ProviderInvoker invoker,
                               ResponseCache cache, RateLimitProperties rateLimitProps,
                               CostCalculator costCalculator) {
        this.repository = repository;
        this.invoker = invoker;
        this.cache = cache;
        this.rateLimitProps = rateLimitProps;
        this.costCalculator = costCalculator;
    }

    @GetMapping("/summary")
    public Map<String, Object> summary(@RequestParam(defaultValue = "60") int minutes) {
        int window = Math.min(Math.max(minutes, 1), 24 * 60);
        OffsetDateTime since = OffsetDateTime.now().minusMinutes(window);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("generatedAt", OffsetDateTime.now().toString());
        payload.put("windowMinutes", window);
        payload.put("currency", costCalculator.currency());

        payload.put("totals", totals(since));
        payload.put("series", series(since));
        payload.put("byModel", byModel(since));
        payload.put("byProvider", byProvider(since));

        // 实时状态（内存）
        payload.put("runtime", Map.of(
                "breakersAndPools", invoker.stats(),
                "cache", cache.stats(),
                "rateLimit", Map.of(
                        "capacity", rateLimitProps.getCapacity(),
                        "refillPerSecond", rateLimitProps.getRefillPerSecond(),
                        "failOpen", rateLimitProps.isFailOpen(),
                        "dailyTokens", rateLimitProps.getDailyTokens(),
                        "monthlyTokens", rateLimitProps.getMonthlyTokens())
        ));

        return payload;
    }

    private Map<String, Object> totals(OffsetDateTime since) {
        Map<String, Object> m = new LinkedHashMap<>();

        // 单行聚合：取第一行即可。
        // 注意仓库方法声明为 List<Object[]>，不能写成 Object[] ——
        // 那样 Spring Data 会把结果列表再套一层数组，row[0] 变成整行数组。
        List<Object[]> rows = repository.windowStats(since);
        if (rows.isEmpty()) {
            return m;
        }
        Object[] row = rows.get(0);
        if (row == null) {
            return m;
        }

        long calls = num(row[0]);
        long degraded = num(row[6]);
        long cacheHits = num(row[7]);
        long costMicros = num(row[8]);

        m.put("calls", calls);
        m.put("degraded", degraded);
        m.put("degradedRate", calls == 0 ? 0.0 : round2(degraded * 100.0 / calls));
        m.put("successRate", calls == 0 ? 0.0 : round2((calls - degraded) * 100.0 / calls));
        m.put("avgMs", num(row[1]));
        m.put("p50Ms", num(row[2]));
        m.put("p95Ms", num(row[3]));
        m.put("p99Ms", num(row[4]));
        m.put("maxMs", num(row[5]));
        m.put("cacheHits", cacheHits);
        m.put("cacheHitRate", calls == 0 ? 0.0 : round2(cacheHits * 100.0 / calls));
        m.put("tokens", num(row[9]));
        m.put("costMicros", costMicros);
        m.put("cost", CostCalculator.formatMicros(costMicros));
        return m;
    }

    private List<Map<String, Object>> series(OffsetDateTime since) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Object[] row : repository.timeSeries(since)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("bucket", row[0]);
            m.put("calls", num(row[1]));
            m.put("degraded", num(row[2]));
            m.put("avgMs", num(row[3]));
            m.put("costMicros", num(row[4]));
            list.add(m);
        }
        return list;
    }

    private List<Map<String, Object>> byModel(OffsetDateTime since) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Object[] row : repository.reportByModel(since)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("model", row[0]);
            m.put("calls", num(row[1]));
            m.put("cost", CostCalculator.formatMicros(num(row[2])));
            m.put("tokens", num(row[3]));
            m.put("cacheHits", num(row[4]));
            list.add(m);
        }
        return list;
    }

    private List<Map<String, Object>> byProvider(OffsetDateTime since) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Object[] row : repository.aggregateByProvider(since)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("provider", row[0]);
            m.put("calls", num(row[1]));
            m.put("avgMs", row[2] == null ? 0 : Math.round(((Number) row[2]).doubleValue()));
            m.put("degraded", num(row[3]));
            list.add(m);
        }
        return list;
    }

    private long num(Object o) {
        return o == null ? 0L : ((Number) o).longValue();
    }

    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
