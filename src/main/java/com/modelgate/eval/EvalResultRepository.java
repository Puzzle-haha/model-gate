package com.modelgate.eval;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface EvalResultRepository extends JpaRepository<EvalResult, Long> {

    List<EvalResult> findByRunIdOrderByIdAsc(Long runId);

    List<EvalResult> findByRunIdAndModelOrderByIdAsc(Long runId, String model);

    /**
     * 按模型聚合：总题数、通过数、总成本、平均耗时。
     *
     * 这是评测报告最重要的一张表 —— 它把"准确率"和"成本"放在一起，
     * 才能回答真正的问题：**多花的钱买到了多少准确率？**
     */
    @Query("""
            select r.model,
                   count(r),
                   sum(case when r.passed = true then 1 else 0 end),
                   coalesce(sum(r.costMicros), 0),
                   coalesce(avg(r.elapsedMs), 0)
            from EvalResult r
            where r.runId = ?1
            group by r.model
            order by r.model
            """)
    List<Object[]> aggregateByModel(Long runId);

    /**
     * 按「模型 × 任务类型」聚合。
     *
     * 为什么必须按任务类型拆开：一个模型总分 60% 可能掩盖了
     * "算术 100%、情感 20%" 这种极端情况。而路由决策恰恰需要这个粒度 ——
     * 你不想按总分路由，你想按「这类任务该用谁」路由。
     */
    @Query("""
            select r.model,
                   r.taskType,
                   count(r),
                   sum(case when r.passed = true then 1 else 0 end),
                   coalesce(sum(r.costMicros), 0),
                   coalesce(avg(r.elapsedMs), 0)
            from EvalResult r
            where r.runId = ?1
            group by r.model, r.taskType
            order by r.model, r.taskType
            """)
    List<Object[]> aggregateByModelAndType(Long runId);
}
