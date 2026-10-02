package com.modelgate.provider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 依据配置构造供应商实例。
 *
 * 为什么不直接用 @Component 声明每种供应商：
 * 供应商来自配置列表，数量在启动时才知道，无法用静态的注解表达。
 * 所以这里用"配置 → 实例"的工厂模式。
 *
 * 关键行为：**配置不完整时跳过，而不是启动失败。**
 * 一个还没填 API key 的供应商，不应该让整个网关起不来 ——
 * 那是很糟糕的开发体验，也会让 CI 无法在没有密钥的环境里跑。
 * 但也不能静默跳过，所以会打日志说明原因。
 */
@Component
public class ProviderFactory {

    private static final Logger log = LoggerFactory.getLogger(ProviderFactory.class);

    private final ProviderProperties props;
    private final RestClient.Builder restClientBuilder;

    /** 已装配的 HTTP 供应商，用于暴露密钥池状态。 */
    private final List<OpenAiCompatibleProvider> created = new ArrayList<>();

    public ProviderFactory(ProviderProperties props, RestClient.Builder restClientBuilder) {
        this.props = props;
        this.restClientBuilder = restClientBuilder;
    }

    /**
     * 构造所有配置完整、已启用的供应商。
     *
     * 幂等：重复调用返回同一批实例（密钥池的冷却状态必须跨调用保持，
     * 每次都新建的话，冷却信息就丢了 —— 密钥会立刻被重复使用）。
     */
    public synchronized List<Provider> createAll() {
        if (!created.isEmpty()) {
            return List.copyOf(created);
        }

        for (ProviderProperties.Definition definition : props.getProviders()) {
            if (!definition.usable()) {
                log.info("跳过供应商 [{}]: {}", definition.getName(), definition.unusableReason());
                continue;
            }

            ApiKeyPool keyPool = new ApiKeyPool(
                    definition.effectiveKeys(),
                    definition.getAuthCooldownMs(),
                    definition.getRateLimitCooldownMs());

            // 每家供应商必须用【独立的 builder】：
            // builder.baseUrl() 是就地修改的，如果共用同一个实例，
            // 后装配的供应商会把前面的 baseUrl 覆盖掉 ——
            // 结果就是所有请求都发到最后那一家的地址。
            // 这种 bug 在单供应商时完全看不出来，加第二家才爆。
            OpenAiCompatibleProvider provider =
                    new OpenAiCompatibleProvider(definition, restClientBuilder.clone(), keyPool);
            created.add(provider);

            log.info("已装配供应商 [{}]: baseUrl={} priority={} timeout={}ms 密钥数={} models={}",
                    definition.getName(), definition.getBaseUrl(), definition.getPriority(),
                    definition.getTimeoutMs(), keyPool.size(), definition.getModels());
        }

        if (created.isEmpty()) {
            log.warn("没有任何真实供应商被装配。当前只有 MockProvider 可用 —— "
                    + "配置 modelgate.providers 并填入 api-key 后即可接入真实上游。");
        }
        return List.copyOf(created);
    }

    /**
     * 各供应商的密钥池状态。**密钥一律脱敏。**
     *
     * 这是排查"为什么请求换了凭证""为什么这家一直失败"的直接入口。
     */
    public synchronized List<Map<String, Object>> keyPoolStats() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (OpenAiCompatibleProvider provider : created) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("provider", provider.name());
            m.put("keyCount", provider.keyPool().size());
            m.put("allCooling", provider.keyPool().allCooling());
            m.put("keys", provider.keyPool().stats());
            list.add(m);
        }
        return list;
    }
}
