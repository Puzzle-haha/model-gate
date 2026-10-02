package com.modelgate.provider;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 真实供应商的配置。
 *
 * 为什么要做成配置驱动，而不是每种供应商写一个 @Component：
 *   DeepSeek、通义（兼容模式）、智谱、各类中转站、vLLM/Ollama ——
 *   它们【讲的是同一套 OpenAI 协议】，差别只在 base_url、密钥和模型名。
 *   为每一家写一个类，等于把同一段代码复制十遍，改一个 bug 要改十处。
 *
 * 所以真正的设计是：**一个 OpenAiCompatibleProvider + 多份配置**。
 * 只有协议确实不同的（比如 Anthropic 原生接口），才值得单独写实现。
 *
 * 配置示例：
 * <pre>
 * modelgate:
 *   providers:
 *     - name: deepseek
 *       base-url: https://api.deepseek.com
 *       api-key: ${DEEPSEEK_API_KEY:}
 *       models: [deepseek-chat, deepseek-reasoner]
 *       timeout-ms: 60000
 * </pre>
 */
@Component
@ConfigurationProperties(prefix = "modelgate")
public class ProviderProperties {

    private List<Definition> providers = new ArrayList<>();

    public List<Definition> getProviders() {
        return providers;
    }

    public void setProviders(List<Definition> providers) {
        this.providers = providers;
    }

    public static class Definition {

        /** 供应商标识，出现在日志和统计里，如 "deepseek"。 */
        private String name;

        /** API 根地址，不含 /chat/completions。 */
        private String baseUrl;

        /** API 密钥（单把）。为空且 api-keys 也为空时，该供应商会被跳过。 */
        private String apiKey;

        /**
         * API 密钥池（多把）。
         *
         * 多把密钥的价值：
         *   1. 单密钥的速率限制有限，轮换能把可用配额叠起来
         *   2. 某把失效时把坏的冷却掉，其余的继续服务，供应商整体不挂
         *
         * 和 api-key 同时配也可以，会被合并去重。
         */
        private List<String> apiKeys = new ArrayList<>();

        /** 鉴权失败（密钥失效）的冷却时长。密钥不会自己恢复，所以默认 10 分钟。 */
        private long authCooldownMs = 600_000;

        /** 被限流的冷却时长。只是"太快了"，默认 30 秒后重试。 */
        private long rateLimitCooldownMs = 30_000;

        /** 该供应商对外暴露的模型名列表。 */
        private List<String> models = new ArrayList<>();

        /**
         * 该供应商的超时预算。
         *
         * 默认 60 秒 —— 真实 LLM 生成一段长文本确实要这么久。
         * 这个值必须明显大于正常响应时间，否则会把正常流量全判成超时。
         */
        private long timeoutMs = 60_000;

        /**
         * 路由优先级，数字越小越优先。
         *
         * 多个供应商支持同一个模型名时，路由器按这个值排序，
         * 从优先级最高的开始尝试，失败再切下一个。
         *
         * 典型用法：把便宜/快的供应商设成 10，把贵但稳的设成 20 作为兜底。
         */
        private int priority = 100;

        private boolean enabled = true;

        /** 合并 api-key 与 api-keys，去重并保留顺序。 */
        public List<String> effectiveKeys() {
            java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>();
            if (apiKey != null && !apiKey.isBlank()) {
                set.add(apiKey.trim());
            }
            if (apiKeys != null) {
                for (String k : apiKeys) {
                    if (k != null && !k.isBlank()) {
                        set.add(k.trim());
                    }
                }
            }
            return List.copyOf(set);
        }

        /** 配置是否可用。不可用的供应商会被跳过，并在启动日志里说明原因。 */
        public boolean usable() {
            return enabled
                    && name != null && !name.isBlank()
                    && baseUrl != null && !baseUrl.isBlank()
                    && !effectiveKeys().isEmpty()
                    && models != null && !models.isEmpty();
        }

        /** 不可用时说明原因，用于启动日志 —— 比"静默跳过"友好得多。 */
        public String unusableReason() {
            if (!enabled) {
                return "enabled=false";
            }
            if (name == null || name.isBlank()) {
                return "缺少 name";
            }
            if (baseUrl == null || baseUrl.isBlank()) {
                return "缺少 base-url";
            }
            if (effectiveKeys().isEmpty()) {
                return "未配置任何 api-key（环境变量 " + name.toUpperCase().replace('-', '_') + "_API_KEY 为空？）";
            }
            if (models == null || models.isEmpty()) {
                return "未配置 models";
            }
            return "配置不完整";
        }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }

        public List<String> getApiKeys() { return apiKeys; }
        public void setApiKeys(List<String> apiKeys) { this.apiKeys = apiKeys; }

        public long getAuthCooldownMs() { return authCooldownMs; }
        public void setAuthCooldownMs(long authCooldownMs) { this.authCooldownMs = authCooldownMs; }

        public long getRateLimitCooldownMs() { return rateLimitCooldownMs; }
        public void setRateLimitCooldownMs(long rateLimitCooldownMs) { this.rateLimitCooldownMs = rateLimitCooldownMs; }

        public List<String> getModels() { return models; }
        public void setModels(List<String> models) { this.models = models; }

        public long getTimeoutMs() { return timeoutMs; }
        public void setTimeoutMs(long timeoutMs) { this.timeoutMs = timeoutMs; }

        public int getPriority() { return priority; }
        public void setPriority(int priority) { this.priority = priority; }

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }
}
