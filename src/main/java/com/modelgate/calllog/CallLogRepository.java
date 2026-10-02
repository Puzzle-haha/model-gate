package com.modelgate.calllog;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;

public interface CallLogRepository extends JpaRepository<CallLog, Long> {

    List<CallLog> findTop50ByOrderByIdDesc();

    long countByStatus(String status);

    long countByProvider(String provider);

    long countByCreatedAtAfter(OffsetDateTime after);

    /**
     * 按供应商聚合：调用量、平均耗时、降级数。
     *
     * 返回 Object[] 而不是自定义 DTO，是为了让早期阶段保持简单。
     * M5 做 Dashboard 时会换成投影接口（Projection），可读性更好。
     */
    @Query("""
            select c.provider,
                   count(c),
                   avg(c.elapsedMs),
                   sum(case when c.status = 'DEGRADED' then 1 else 0 end)
            from CallLog c
            where c.createdAt > ?1
            group by c.provider
            order by count(c) desc
            """)
    List<Object[]> aggregateByProvider(OffsetDateTime since);

    /**
     * 成本报表：按模型聚合。
     *
     * 返回 [模型, 调用次数, 总成本微元, 总 token, 缓存命中次数]。
     * 缓存命中次数是这里最关键的一列 —— "缓存到底省了多少"全靠它。
     */
    @Query("""
            select c.model,
                   count(c),
                   coalesce(sum(c.costMicros), 0),
                   coalesce(sum(c.totalTokens), 0),
                   sum(case when c.cacheHit = true then 1 else 0 end)
            from CallLog c
            where c.createdAt > ?1
            group by c.model
            order by coalesce(sum(c.costMicros), 0) desc
            """)
    List<Object[]> reportByModel(OffsetDateTime since);

    /** 成本报表：按调用方聚合。 */
    @Query("""
            select c.tenant,
                   count(c),
                   coalesce(sum(c.costMicros), 0),
                   sum(case when c.cacheHit = true then 1 else 0 end)
            from CallLog c
            where c.createdAt > ?1
            group by c.tenant
            order by coalesce(sum(c.costMicros), 0) desc
            """)
    List<Object[]> reportByTenant(OffsetDateTime since);

    /** 时间窗口内的总成本与缓存命中数。 */
    @Query("""
            select coalesce(sum(c.costMicros), 0),
                   count(c),
                   sum(case when c.cacheHit = true then 1 else 0 end)
            from CallLog c
            where c.createdAt > ?1
            """)
    List<Object[]> totalsSince(OffsetDateTime since);

    /**
     * 窗口内的延迟百分位数。
     *
     * 为什么必须用原生 SQL：JPQL 不支持窗口函数，而算百分位数绕不开它。
     * 用 PERCENT_RANK() 给每行算出它在分布中的位置，再取对应位置的耗时。
     *
     * 为什么不用平均值代替：**平均值会骗人。**
     * 99 个请求 10ms、1 个请求 10s，平均值 110ms 看起来很好，
     * 但那个倒霉用户等了 10 秒。P95/P99 才能暴露这种长尾。
     *
     * MySQL 没有 PERCENTILE_CONT，所以用这个写法近似。
     *
     * ⚠️ 返回 List&lt;Object[]&gt; 而不是 Object[]：
     * 声明成 Object[] 时，Spring Data 会理解成"把结果列表转成数组"，
     * 于是拿到的其实是【装着行数组的数组】，row[0] 是整行而不是第一列，
     * 后续 cast 到 Number 就 ClassCastException。这个坑很隐蔽，因为
     * 编译期完全看不出问题。
     *
     * ⚠️ 另一个坑：created_at 列里存的是 **UTC**（见 CallLog 的说明），
     * 所以这里传进来的 since 也必须是能被正确转成 UTC 的 OffsetDateTime。
     * 写原生 SQL 做排查时不要用 NOW()，要用 UTC_TIMESTAMP()。
     */
    @Query(value = """
            SELECT COUNT(*)                                                        AS calls,
                   COALESCE(AVG(t.elapsed_ms), 0)                                  AS avg_ms,
                   COALESCE(MAX(CASE WHEN t.pct <= 0.50 THEN t.elapsed_ms END), 0) AS p50,
                   COALESCE(MAX(CASE WHEN t.pct <= 0.95 THEN t.elapsed_ms END), 0) AS p95,
                   COALESCE(MAX(CASE WHEN t.pct <= 0.99 THEN t.elapsed_ms END), 0) AS p99,
                   COALESCE(MAX(t.elapsed_ms), 0)                                  AS max_ms,
                   COALESCE(SUM(CASE WHEN t.status = 'DEGRADED' THEN 1 ELSE 0 END), 0) AS degraded,
                   COALESCE(SUM(CASE WHEN t.cache_hit + 0 = 1 THEN 1 ELSE 0 END), 0)   AS cache_hits,
                   COALESCE(SUM(t.cost_micros), 0)                                 AS cost_micros,
                   COALESCE(SUM(t.total_tokens), 0)                                AS tokens
            FROM (
                SELECT elapsed_ms, status, cache_hit, cost_micros, total_tokens,
                       PERCENT_RANK() OVER (ORDER BY elapsed_ms) AS pct
                FROM call_log
                WHERE created_at > :since
            ) t
            """, nativeQuery = true)
    List<Object[]> windowStats(@Param("since") OffsetDateTime since);

    /** 按分钟聚合的时间序列，用于 Dashboard 的折线图。 */
    @Query(value = """
            SELECT DATE_FORMAT(created_at, '%Y-%m-%d %H:%i') AS bucket,
                   COUNT(*)                                  AS calls,
                   COALESCE(SUM(CASE WHEN status = 'DEGRADED' THEN 1 ELSE 0 END), 0) AS degraded,
                   COALESCE(ROUND(AVG(elapsed_ms)), 0)       AS avg_ms,
                   COALESCE(SUM(cost_micros), 0)             AS cost_micros
            FROM call_log
            WHERE created_at > :since
            GROUP BY DATE_FORMAT(created_at, '%Y-%m-%d %H:%i')
            ORDER BY bucket
            """, nativeQuery = true)
    List<Object[]> timeSeries(@Param("since") OffsetDateTime since);
}
