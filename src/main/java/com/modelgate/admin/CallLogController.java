package com.modelgate.admin;

import com.modelgate.calllog.CallLog;
import com.modelgate.calllog.CallLogRepository;
import com.modelgate.calllog.CallLogWriter;
import com.modelgate.pricing.CostCalculator;
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
 * 调用日志查询（内部用）。
 *
 * M5 做 Dashboard 时，前端就是调这些接口拿数据的。
 * 现在先手工 curl 验证"日志确实落库了"。
 */
@RestController
@RequestMapping("/api/logs")
public class CallLogController {

    private final CallLogRepository repository;
    private final CallLogWriter writer;
    private final CostCalculator costCalculator;

    public CallLogController(CallLogRepository repository, CallLogWriter writer,
                             CostCalculator costCalculator) {
        this.repository = repository;
        this.writer = writer;
        this.costCalculator = costCalculator;
    }

    /** 最近 50 条调用记录。 */
    @GetMapping("/recent")
    public List<Map<String, Object>> recent() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (CallLog c : repository.findTop50ByOrderByIdDesc()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.getId());
            m.put("requestId", c.getRequestId());
            m.put("provider", c.getProvider());
            m.put("requestedModel", c.getRequestedModel());
            m.put("model", c.getModel());
            m.put("status", c.getStatus());
            m.put("attempts", c.getAttempts());
            m.put("elapsedMs", c.getElapsedMs());
            m.put("totalTokens", c.getTotalTokens());
            m.put("errorType", c.getErrorType());
            m.put("breakerState", c.getBreakerState());
            // 成本用「元」展示，同时保留微元原值便于前端精确计算
            m.put("costMicros", c.getCostMicros());
            m.put("cost", CostCalculator.formatMicros(c.getCostMicros()));
            m.put("cacheHit", c.isCacheHit());
            m.put("createdAt", c.getCreatedAt() == null ? null : c.getCreatedAt().toString());
            list.add(m);
        }
        return list;
    }

    /**
     * 成本报表。
     *
     * 返回总额 + 按模型 + 按调用方三个维度。
     * "缓存省了多少钱"这项是 M4 最核心的产出 ——
     * 没有它，缓存就只是一个"感觉更快了"的模糊说法。
     */
    @GetMapping("/report")
    public Map<String, Object> report(@RequestParam(defaultValue = "60") int minutes) {
        OffsetDateTime since = OffsetDateTime.now().minusMinutes(minutes);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("windowMinutes", minutes);
        result.put("currency", costCalculator.currency());

        // 总额
        List<Object[]> totals = repository.totalsSince(since);
        long totalMicros = 0;
        long totalCalls = 0;
        long cacheHits = 0;
        if (!totals.isEmpty() && totals.get(0) != null) {
            Object[] row = totals.get(0);
            totalMicros = num(row[0]);
            totalCalls = num(row[1]);
            cacheHits = num(row[2]);
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("calls", totalCalls);
        summary.put("cacheHits", cacheHits);
        summary.put("cacheHitRate", totalCalls == 0 ? 0.0
                : Math.round(cacheHits * 10000.0 / totalCalls) / 100.0);
        summary.put("totalCostMicros", totalMicros);
        summary.put("totalCost", CostCalculator.formatMicros(totalMicros));
        summary.put("avgCostPerCall", CostCalculator.formatMicros(
                totalCalls == 0 ? 0 : totalMicros / totalCalls));
        result.put("summary", summary);

        // 按模型
        List<Map<String, Object>> byModel = new ArrayList<>();
        for (Object[] row : repository.reportByModel(since)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("model", row[0]);
            m.put("calls", num(row[1]));
            m.put("costMicros", num(row[2]));
            m.put("cost", CostCalculator.formatMicros(num(row[2])));
            m.put("tokens", num(row[3]));
            m.put("cacheHits", num(row[4]));
            byModel.add(m);
        }
        result.put("byModel", byModel);

        // 按调用方
        List<Map<String, Object>> byTenant = new ArrayList<>();
        for (Object[] row : repository.reportByTenant(since)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("tenant", row[0]);
            m.put("calls", num(row[1]));
            m.put("costMicros", num(row[2]));
            m.put("cost", CostCalculator.formatMicros(num(row[2])));
            m.put("cacheHits", num(row[3]));
            byTenant.add(m);
        }
        result.put("byTenant", byTenant);

        return result;
    }

    private long num(Object o) {
        return o == null ? 0L : ((Number) o).longValue();
    }

    /** 按供应商聚合：调用量、平均耗时、降级数。 */
    @GetMapping("/aggregate")
    public List<Map<String, Object>> aggregate(@RequestParam(defaultValue = "60") int minutes) {
        OffsetDateTime since = OffsetDateTime.now().minusMinutes(minutes);
        List<Map<String, Object>> list = new ArrayList<>();
        for (Object[] row : repository.aggregateByProvider(since)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("provider", row[0]);
            m.put("calls", row[1]);
            m.put("avgElapsedMs", row[2] == null ? null : Math.round(((Number) row[2]).doubleValue()));
            m.put("degraded", row[3]);
            list.add(m);
        }
        return list;
    }

    /**
     * 写入器自身状态 —— 用来判断"日志通道"是否健康。
     *
     * 特别关注 dropped：它大于 0 说明数据库写入跟不上，
     * 这时候日志已经在丢了，但服务本身是好的（这正是当初的设计取舍）。
     */
    @GetMapping("/writer-stats")
    public Map<String, Object> writerStats() {
        return writer.stats();
    }
}
