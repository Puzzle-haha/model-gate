package com.modelgate.calllog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * 一次网关调用的记录。
 *
 * 这张表是整个项目的数据地基 —— 后面几乎每个里程碑都要读它：
 *   M4 成本核算   → 按 token 和模型聚合
 *   M5 可观测性   → P95 延迟、错误率、降级率
 *   M6 评测回归   → 按评测批次对比模型质量
 *
 * 设计取舍：
 *
 * 1. 刻意【不存】请求和响应的完整正文。
 *    正文可能很大（几千 token），存进去会让这张表迅速膨胀到 GB 级，
 *    查询和聚合都会变慢。真正需要正文的场景（调试、评测）应该单独存，
 *    并带 TTL 和脱敏。这是个典型的"日志表 vs 明细表"分离决策。
 *
 * 2. token 字段用 Integer 可空。
 *    不是所有上游都会返回用量。用 null 明确表示"上游没给"，
 *    而不是伪造 0 —— 成本核算必须能区分"用量为 0"和"用量未知"。
 *
 * 3. created_at 在【构造时】就固定，不是 @PrePersist 时生成。
 *    因为日志是异步批量落库的，@PrePersist 的时间会比真实调用晚几百毫秒
 *    甚至几秒，用来算延迟和做时间聚合就失真了。
 */
@Entity
@Table(name = "call_log", indexes = {
        @Index(name = "idx_call_log_created_at", columnList = "created_at"),
        @Index(name = "idx_call_log_provider_created", columnList = "provider, created_at"),
        @Index(name = "idx_call_log_model_created", columnList = "model, created_at")
})
public class CallLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 网关生成的请求 id，用于串联一次调用的所有日志。 */
    @Column(name = "request_id", nullable = false, length = 64)
    private String requestId;

    /** 调用方标识（M3 接入密钥池后由 API key 映射而来）。 */
    @Column(name = "tenant", length = 64)
    private String tenant;

    /** 客户端请求的模型名，可能是 "auto"。 */
    @Column(name = "requested_model", length = 64)
    private String requestedModel;

    /** 实际使用的供应商。 */
    @Column(name = "provider", nullable = false, length = 32)
    private String provider;

    /** 实际使用的模型。 */
    @Column(name = "model", nullable = false, length = 128)
    private String model;

    /** SUCCESS 或 DEGRADED。 */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    /** 错误分类，如 timeout / upstream_5xx / circuit_open。 */
    @Column(name = "error_type", length = 48)
    private String errorType;

    @Column(name = "error_message", length = 512)
    private String errorMessage;

    /** 实际尝试次数（含首次）。 */
    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "prompt_tokens")
    private Integer promptTokens;

    @Column(name = "completion_tokens")
    private Integer completionTokens;

    @Column(name = "total_tokens")
    private Integer totalTokens;

    /** 容错层耗时（含重试与退避）。 */
    @Column(name = "elapsed_ms", nullable = false)
    private long elapsedMs;

    /** 结束时该供应商的熔断器状态。 */
    @Column(name = "breaker_state", length = 16)
    private String breakerState;

    /** 本次是否命中了响应缓存（命中的话没有产生上游调用，成本为 0）。 */
    @Column(name = "cache_hit", nullable = false)
    private boolean cacheHit;

    /**
     * 本次调用的成本，单位【微元】（1 元 = 1,000,000 微元）。
     *
     * 为什么用整数存钱而不是 double：
     *   浮点数无法精确表示 0.1 这类十进制小数，累加几百万次之后的误差
     *   足以让账对不上。金额必须用整数最小单位（分、微元）或 BigDecimal。
     *   这里选整数是为了数据库聚合（SUM）又快又准。
     */
    @Column(name = "cost_micros", nullable = false)
    private long costMicros;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected CallLog() {
        // JPA 要求无参构造
    }

    private CallLog(String requestId, String tenant, String requestedModel, String provider,
                    String model, String status, String errorType, String errorMessage,
                    int attempts, Integer promptTokens, Integer completionTokens, Integer totalTokens,
                    long elapsedMs, String breakerState, boolean cacheHit, long costMicros,
                    OffsetDateTime createdAt) {
        this.requestId = requestId;
        this.tenant = tenant;
        this.requestedModel = requestedModel;
        this.provider = provider;
        this.model = model;
        this.status = status;
        this.errorType = errorType;
        this.errorMessage = errorMessage;
        this.attempts = attempts;
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.totalTokens = totalTokens;
        this.elapsedMs = elapsedMs;
        this.breakerState = breakerState;
        this.cacheHit = cacheHit;
        this.costMicros = costMicros;
        this.createdAt = createdAt;
    }

    /**
     * 构造一条成功或降级的记录。
     *
     * 用静态工厂而不是公开构造器：字段太多，公开构造器很容易传错顺序，
     * 而且以后加字段会破坏所有调用点。
     */
    public static CallLog record(String requestId, String tenant, String requestedModel,
                                 String provider, String model, boolean success,
                                 String errorType, String errorMessage, int attempts,
                                 Integer promptTokens, Integer completionTokens, Integer totalTokens,
                                 long elapsedMs, String breakerState,
                                 boolean cacheHit, long costMicros) {
        return new CallLog(requestId, tenant, requestedModel, provider, model,
                success ? "SUCCESS" : "DEGRADED",
                truncate(errorType, 48), truncate(errorMessage, 512),
                attempts, promptTokens, completionTokens, totalTokens,
                elapsedMs, truncate(breakerState, 16), cacheHit, costMicros,
                OffsetDateTime.now());
    }

    /** 上游的报错信息可能很长，落库前必须截断，否则插入会直接失败。 */
    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    public Long getId() { return id; }
    public String getRequestId() { return requestId; }
    public String getTenant() { return tenant; }
    public String getRequestedModel() { return requestedModel; }
    public String getProvider() { return provider; }
    public String getModel() { return model; }
    public String getStatus() { return status; }
    public String getErrorType() { return errorType; }
    public String getErrorMessage() { return errorMessage; }
    public int getAttempts() { return attempts; }
    public Integer getPromptTokens() { return promptTokens; }
    public Integer getCompletionTokens() { return completionTokens; }
    public Integer getTotalTokens() { return totalTokens; }
    public long getElapsedMs() { return elapsedMs; }
    public String getBreakerState() { return breakerState; }
    public boolean isCacheHit() { return cacheHit; }
    public long getCostMicros() { return costMicros; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
