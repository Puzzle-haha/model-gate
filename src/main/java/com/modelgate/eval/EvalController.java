package com.modelgate.eval;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 评测接口。
 *
 * 跑一次评测是同步的 —— 对当前规模（十几道题 × 几个模型）足够，
 * 而且同步返回能立刻看到结果，调试体验好得多。
 *
 * 如果任务数上去（几百道题 × 十几个模型），同步会超时，
 * 那时应该改成异步：提交任务返回 runId，前端轮询进度。
 * 这个演进路径值得在面试里讲 —— **先做对，再做快**。
 */
@RestController
@RequestMapping("/api/eval")
public class EvalController {

    private final EvalService evalService;

    public EvalController(EvalService evalService) {
        this.evalService = evalService;
    }

    /** 查看评测集内容（跑之前先确认题目和期望答案）。 */
    @GetMapping("/fixtures")
    public Map<String, Object> fixtures() throws IOException {
        EvalService.FixtureSet set = evalService.loadFixtures();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", set.name());
        m.put("description", set.description());
        m.put("taskCount", set.tasks().size());
        m.put("tasks", set.tasks());
        return m;
    }

    /**
     * 跑一次评测。
     *
     * @param models 逗号分隔的模型名，如 eval-strong,eval-weak
     */
    @PostMapping("/run")
    public Map<String, Object> run(@RequestParam String models) throws IOException {
        List<String> list = Arrays.stream(models.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        if (list.isEmpty()) {
            return Map.of("error", "至少要指定一个模型，例如 models=eval-strong,eval-weak");
        }
        return evalService.run(list);
    }

    /** 历史评测运行列表。 */
    @GetMapping("/runs")
    public List<Map<String, Object>> runs() {
        return evalService.listRuns();
    }

    /** 某次运行的完整报告。 */
    @GetMapping("/runs/{id}")
    public Map<String, Object> report(@PathVariable Long id) {
        return evalService.report(id);
    }

    /** 某次运行的逐题明细（可选按模型过滤），用于复盘错在哪。 */
    @GetMapping("/runs/{id}/results")
    public List<Map<String, Object>> results(@PathVariable Long id,
                                             @RequestParam(required = false) String model) {
        return evalService.results(id, model);
    }
}
