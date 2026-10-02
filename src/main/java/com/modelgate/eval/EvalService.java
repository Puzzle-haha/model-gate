package com.modelgate.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.modelgate.gateway.GatewayService;
import com.modelgate.pricing.CostCalculator;
import com.modelgate.provider.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 评测编排。
 *
 * ============================================================================
 * 这是整个项目里最接近"Agent 工程"本质的一段
 * ============================================================================
 * 写一个能调用模型的接口不难，难的是**回答"这个改动到底让系统变好了还是变差了"**。
 * LLM 的输出是概率性的，同一个 prompt 今天对明天错；换个模型、调个参数、
 * 改一句提示词，效果都可能变化。没有评测，你所有的"优化"都是凭感觉。
 *
 * 所以评测系统要提供三件事：
 *   1. **可重复**：同样的输入必须能得到可比较的输出（温度固定、绕过缓存）
 *   2. **可量化**：分数、成本、延迟都要有数字，不能是"感觉好多了"
 *   3. **可归因**：分数掉了要能定位到是哪类任务、哪个模型、哪道题
 *
 * 第 3 点尤其容易被忽略。只报一个总分 78%，你完全不知道下一步该做什么；
 * 拆到「模型 × 任务类型」才能看出"算术没问题、情感分类崩了"。
 */
@Service
public class EvalService {

    private static final Logger log = LoggerFactory.getLogger(EvalService.class);

    private final ObjectMapper mapper;
    private final Scorer scorer;
    private final GatewayService gateway;
    private final CostCalculator costCalculator;
    private final EvalRunRepository runRepository;
    private final EvalResultRepository resultRepository;

    public EvalService(ObjectMapper mapper, Scorer scorer, GatewayService gateway,
                       CostCalculator costCalculator,
                       EvalRunRepository runRepository,
                       EvalResultRepository resultRepository) {
        this.mapper = mapper;
        this.scorer = scorer;
        this.gateway = gateway;
        this.costCalculator = costCalculator;
        this.runRepository = runRepository;
        this.resultRepository = resultRepository;
    }

    /** 加载评测集。 */
    public FixtureSet loadFixtures() throws IOException {
        ClassPathResource resource = new ClassPathResource("eval/fixtures.json");
        try (InputStream in = resource.getInputStream()) {
            return mapper.readValue(in, FixtureSet.class);
        }
    }

    /**
     * 跑一次评测：把评测集里的每条任务，在每个模型上都跑一遍。
     *
     * @param models 参与评测的模型名列表
     */
    public Map<String, Object> run(List<String> models) throws IOException {
        FixtureSet fixtures = loadFixtures();
        OffsetDateTime startedAt = OffsetDateTime.now();
        long t0 = System.currentTimeMillis();

        EvalRun run = runRepository.save(new EvalRun(
                fixtures.name(), String.join(",", models), fixtures.tasks().size(), startedAt));

        int totalCalls = 0;
        int passedCount = 0;
        long totalCostMicros = 0;

        try {
            for (EvalTask task : fixtures.tasks()) {
                for (String model : models) {
                    EvalResult result = runOne(run.getId(), task, model);
                    resultRepository.save(result);
                    totalCalls++;
                    if (result.isPassed()) {
                        passedCount++;
                    }
                    totalCostMicros += result.getCostMicros();
                }
            }
        } catch (Exception e) {
            log.error("评测运行失败 runId={}", run.getId(), e);
            run.fail();
            runRepository.save(run);
            throw e;
        }

        run.finish(totalCalls, passedCount, System.currentTimeMillis() - t0, totalCostMicros);
        runRepository.save(run);

        log.info("评测完成 runId={} 调用 {} 次 通过 {} 次 成本 {} 元",
                run.getId(), totalCalls, passedCount, CostCalculator.formatMicros(totalCostMicros));

        return report(run.getId());
    }

    /** 跑单条任务，并打分。 */
    private EvalResult runOne(Long runId, EvalTask task, String model) {
        // 温度固定为 0：评测必须可重复。温度不为 0 的话，
        // 同一道题两次跑出的答案不同，分数波动就分不清是模型问题还是随机性。
        GatewayService.GatewayResult result = gateway.chatForEval(
                model,
                List.of(new ChatMessage("user", task.prompt())),
                0.0d,
                256);

        String output = result.invocation().content();
        boolean passed = result.invocation().success() && scorer.score(task, output);

        long costMicros = 0L;
        Integer totalTokens = null;
        if (result.invocation().response() != null) {
            totalTokens = result.invocation().response().totalTokens();
            if (result.invocation().success()) {
                costMicros = costCalculator.costMicros(model,
                        result.invocation().response().promptTokens(),
                        result.invocation().response().completionTokens());
            }
        }

        return new EvalResult(runId, task.id(), task.type(), model, result.provider(),
                task.expected(), output, passed,
                result.invocation().elapsedMs(), costMicros, totalTokens,
                result.invocation().errorType());
    }

    // ------------------------------------------------------------------ 报告

    /** 组装评测报告。 */
    public Map<String, Object> report(Long runId) {
        EvalRun run = runRepository.findById(runId).orElse(null);
        if (run == null) {
            return Map.of("error", "评测运行不存在: " + runId);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("run", runSummary(run));
        payload.put("byModel", byModel(runId));
        payload.put("byModelAndType", byModelAndType(runId));
        payload.put("failures", failures(runId));
        return payload;
    }

    private Map<String, Object> runSummary(EvalRun run) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", run.getId());
        m.put("name", run.getName());
        m.put("models", List.of(run.getModels().split(",")));
        m.put("taskCount", run.getTaskCount());
        m.put("totalCalls", run.getTotalCalls());
        m.put("passed", run.getPassedCount());
        m.put("accuracy", run.getTotalCalls() == 0 ? 0.0
                : round2(run.getPassedCount() * 100.0 / run.getTotalCalls()));
        m.put("elapsedMs", run.getElapsedMs());
        m.put("costMicros", run.getCostMicros());
        m.put("cost", CostCalculator.formatMicros(run.getCostMicros()));
        m.put("status", run.getStatus());
        m.put("startedAt", run.getStartedAt().toString());
        m.put("finishedAt", run.getFinishedAt() == null ? null : run.getFinishedAt().toString());
        return m;
    }

    /**
     * 按模型汇总。**这张表是选型的核心依据。**
     *
     * 关键在于把准确率和成本放在一起，并算出「每个正确答案的成本」——
     * 那才是决策指标。单看准确率会永远选最贵的模型，
     * 单看成本会永远选最便宜的，两者都没有意义。
     */
    private List<Map<String, Object>> byModel(Long runId) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Object[] row : resultRepository.aggregateByModel(runId)) {
            String model = (String) row[0];
            long calls = num(row[1]);
            long passed = num(row[2]);
            long costMicros = num(row[3]);
            long avgMs = Math.round(((Number) row[4]).doubleValue());

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("model", model);
            m.put("calls", calls);
            m.put("passed", passed);
            m.put("accuracy", calls == 0 ? 0.0 : round2(passed * 100.0 / calls));
            m.put("costMicros", costMicros);
            m.put("cost", CostCalculator.formatMicros(costMicros));
            m.put("avgMs", avgMs);
            // 每个正确答案的成本：选型的真正依据
            m.put("costPerCorrectMicros", passed == 0 ? -1 : costMicros / passed);
            m.put("costPerCorrect", passed == 0 ? "—"
                    : CostCalculator.formatMicros(costMicros / passed));
            list.add(m);
        }
        return list;
    }

    /** 按「模型 × 任务类型」汇总。用于定位"哪类任务该用谁"。 */
    private List<Map<String, Object>> byModelAndType(Long runId) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Object[] row : resultRepository.aggregateByModelAndType(runId)) {
            long calls = num(row[2]);
            long passed = num(row[3]);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("model", row[0]);
            m.put("taskType", row[1]);
            m.put("calls", calls);
            m.put("passed", passed);
            m.put("accuracy", calls == 0 ? 0.0 : round2(passed * 100.0 / calls));
            m.put("cost", CostCalculator.formatMicros(num(row[4])));
            m.put("avgMs", Math.round(((Number) row[5]).doubleValue()));
            list.add(m);
        }
        return list;
    }

    /** 未通过的明细，带原文，便于复盘。 */
    private List<Map<String, Object>> failures(Long runId) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (EvalResult r : resultRepository.findByRunIdOrderByIdAsc(runId)) {
            if (r.isPassed()) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("taskId", r.getTaskId());
            m.put("taskType", r.getTaskType());
            m.put("model", r.getModel());
            m.put("expected", r.getExpectedAnswer());
            m.put("actual", r.getActualOutput());
            m.put("errorType", r.getErrorType());
            list.add(m);
        }
        return list;
    }

    public List<Map<String, Object>> listRuns() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (EvalRun run : runRepository.findTop20ByOrderByIdDesc()) {
            list.add(runSummary(run));
        }
        return list;
    }

    public List<Map<String, Object>> results(Long runId, String model) {
        List<EvalResult> rows = model == null || model.isBlank()
                ? resultRepository.findByRunIdOrderByIdAsc(runId)
                : resultRepository.findByRunIdAndModelOrderByIdAsc(runId, model);

        List<Map<String, Object>> list = new ArrayList<>();
        for (EvalResult r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("taskId", r.getTaskId());
            m.put("taskType", r.getTaskType());
            m.put("model", r.getModel());
            m.put("expected", r.getExpectedAnswer());
            m.put("actual", r.getActualOutput());
            m.put("passed", r.isPassed());
            m.put("elapsedMs", r.getElapsedMs());
            m.put("cost", CostCalculator.formatMicros(r.getCostMicros()));
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

    /** 评测集文件的结构。 */
    public record FixtureSet(String name, String description, List<EvalTask> tasks) {
    }
}
