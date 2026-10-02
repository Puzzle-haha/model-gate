package com.modelgate.calllog;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

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
}
