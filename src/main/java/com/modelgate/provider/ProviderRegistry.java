package com.modelgate.provider;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 供应商注册表 —— 负责"这个模型谁能提供"。
 *
 * M1 阶段是简单的按模型名精确匹配。
 * M2 会在这里加入：健康度排序、成本排序、按权重/策略选路、故障转移。
 */
@Component
public class ProviderRegistry {

    private final List<Provider> providers;

    /**
     * 供应商来自两个来源：
     *   1. Spring 容器里的 Provider bean（如 MockProvider）
     *   2. 配置驱动的真实供应商（ProviderFactory 按 modelgate.providers 构造）
     *
     * 这里把它们合成一份清单。顺序上把 Spring bean 放前面，
     * 但真正的"选谁"是 {@link #resolve(String)} 按模型名精确匹配的，
     * 顺序只影响同名模型的优先级（M2 会把这里换成显式的优先级策略）。
     */
    public ProviderRegistry(List<Provider> providers, ProviderFactory factory) {
        List<Provider> all = new ArrayList<>(providers);
        all.addAll(factory.createAll());
        this.providers = List.copyOf(all);
    }

    /** 把所有供应商声称支持的模型汇总去重。 */
    public List<String> allModels() {
        List<String> models = new ArrayList<>();
        for (Provider p : providers) {
            models.addAll(p.models());
        }
        return models.stream().distinct().toList();
    }

    /**
     * 按模型名解析出【候选供应商列表】，已按优先级排序（数字小的在前）。
     *
     * 返回列表而不是单个，是故障转移的基础：调用方按顺序尝试，
     * 前一个失败了就切下一个。
     *
     * @throws UnknownModelException 没有任何供应商支持这个模型
     */
    public List<Provider> candidates(String model) {
        List<Provider> matches = new ArrayList<>();
        for (Provider p : providers) {
            if (p.models().contains(model)) {
                matches.add(p);
            }
        }
        if (matches.isEmpty()) {
            throw new UnknownModelException(model, allModels());
        }
        // 优先级数字小的排前面；相同优先级保持装配顺序（稳定排序）
        matches.sort(Comparator.comparingInt(Provider::priority));
        return List.copyOf(matches);
    }

    /**
     * 解析出优先级最高的那一个供应商。
     *
     * @throws UnknownModelException 没有任何供应商支持这个模型
     */
    public Provider resolve(String model) {
        return candidates(model).get(0);
    }

    public List<Provider> all() {
        return providers;
    }

    /** 给管理接口用的供应商清单。 */
    public List<Map<String, Object>> inventory() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Provider p : providers) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("provider", p.name());
            item.put("priority", p.priority());
            item.put("timeoutMs", p.defaultTimeoutMs());
            item.put("models", p.models());
            list.add(item);
        }
        return list;
    }

    /**
     * 给定模型名，返回会按什么顺序尝试哪些供应商。
     *
     * 这是排查"为什么请求打到那家去了"最直接的接口 ——
     * 路由这种逻辑，能一眼看见比读代码猜要可靠得多。
     */
    public List<Map<String, Object>> routeFor(String model) {
        List<Map<String, Object>> list = new ArrayList<>();
        int order = 1;
        for (Provider p : candidates(model)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("order", order++);
            item.put("provider", p.name());
            item.put("priority", p.priority());
            item.put("timeoutMs", p.defaultTimeoutMs());
            list.add(item);
        }
        return list;
    }
}
