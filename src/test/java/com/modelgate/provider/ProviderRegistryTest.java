package com.modelgate.provider;

import com.modelgate.resilience.LlmCallException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 供应商路由测试。
 *
 * 为什么值得单测：优先级的 bug 表现为"请求打到了不该打的那家去"，
 * 而不是报错。这类问题在日志里只看得见结果（provider=xxx），
 * 看不出"本该是另一家"，所以很难排查。
 */
class ProviderRegistryTest {

    /** 测试用供应商。record 自动生成 name()/models()/priority() 三个访问器。 */
    private record TestProvider(String name, List<String> models, int priority) implements Provider {
        @Override
        public ProviderResponse chat(ProviderRequest request) throws LlmCallException {
            return new ProviderResponse("ok", request.model(), 1, 1, "stop");
        }
    }

    /**
     * 构造真实的 ProviderFactory，不用 Mockito。
     *
     * 好处：测试不依赖 mock 框架的行为，构建也不会打 agent 警告。
     * 没有配置项时 createAll() 自然返回空列表，正好是我们想要的默认值。
     */
    private ProviderFactory factoryWith(ProviderProperties.Definition... definitions) {
        ProviderProperties props = new ProviderProperties();
        props.setProviders(new ArrayList<>(List.of(definitions)));
        return new ProviderFactory(props, RestClient.builder());
    }

    private ProviderRegistry registry(Provider... providers) {
        return new ProviderRegistry(List.of(providers), factoryWith());
    }

    @Test
    @DisplayName("候选列表按优先级升序排列（数字小的先试）")
    void candidatesSortedByPriority() {
        ProviderRegistry registry = registry(
                new TestProvider("slow-expensive", List.of("m"), 100),
                new TestProvider("fast-cheap", List.of("m"), 10),
                new TestProvider("fallback", List.of("m"), 50));

        List<Provider> candidates = registry.candidates("m");

        assertThat(candidates).extracting(Provider::name)
                .containsExactly("fast-cheap", "fallback", "slow-expensive");
    }

    @Test
    @DisplayName("resolve 返回优先级最高的那个")
    void resolveReturnsHighestPriority() {
        ProviderRegistry registry = registry(
                new TestProvider("backup", List.of("m"), 90),
                new TestProvider("primary", List.of("m"), 5));

        assertThat(registry.resolve("m").name()).isEqualTo("primary");
    }

    @Test
    @DisplayName("只返回支持该模型的供应商 —— 不支持的不该出现在候选里")
    void onlyProvidersSupportingModel() {
        ProviderRegistry registry = registry(
                new TestProvider("supports", List.of("target"), 10),
                new TestProvider("does-not-support", List.of("other"), 1));

        assertThat(registry.candidates("target")).extracting(Provider::name)
                .containsExactly("supports");
    }

    @Test
    @DisplayName("相同优先级时保持装配顺序（稳定排序）")
    void stableOrderForEqualPriority() {
        ProviderRegistry registry = registry(
                new TestProvider("first", List.of("m"), 50),
                new TestProvider("second", List.of("m"), 50),
                new TestProvider("third", List.of("m"), 50));

        assertThat(registry.candidates("m")).extracting(Provider::name)
                .containsExactly("first", "second", "third");
    }

    @Test
    @DisplayName("没有供应商支持该模型时抛 UnknownModelException，并列出可用模型")
    void unknownModelThrows() {
        ProviderRegistry registry = registry(
                new TestProvider("p", List.of("a", "b"), 10));

        assertThatThrownBy(() -> registry.candidates("nope"))
                .isInstanceOf(UnknownModelException.class)
                .hasMessageContaining("nope")
                .hasMessageContaining("a")
                .hasMessageContaining("b");
    }

    @Test
    @DisplayName("allModels 汇总去重")
    void allModelsAreDistinct() {
        ProviderRegistry registry = registry(
                new TestProvider("p1", List.of("shared", "only-p1"), 10),
                new TestProvider("p2", List.of("shared", "only-p2"), 20));

        assertThat(registry.allModels())
                .containsExactlyInAnyOrder("shared", "only-p1", "only-p2");
    }

    @Test
    @DisplayName("routeFor 返回带顺序号的决策链 —— 用于排查「请求为什么打到那家」")
    void routeForShowsDecisionChain() {
        ProviderRegistry registry = registry(
                new TestProvider("broken", List.of("m"), 10),
                new TestProvider("healthy", List.of("m"), 20));

        List<java.util.Map<String, Object>> route = registry.routeFor("m");

        assertThat(route).hasSize(2);
        assertThat(route.get(0)).containsEntry("order", 1).containsEntry("provider", "broken");
        assertThat(route.get(1)).containsEntry("order", 2).containsEntry("provider", "healthy");
    }

    @Test
    @DisplayName("inventory 暴露优先级与超时预算")
    void inventoryExposesRoutingAttributes() {
        ProviderRegistry registry = registry(
                new TestProvider("p", List.of("m"), 42));

        assertThat(registry.inventory()).hasSize(1);
        assertThat(registry.inventory().get(0))
                .containsEntry("provider", "p")
                .containsEntry("priority", 42);
    }

    @Test
    @DisplayName("工厂创建的配置驱动供应商与容器里的供应商会合并")
    void mergesFactoryProviders() {
        ProviderProperties.Definition definition = new ProviderProperties.Definition();
        definition.setName("config-driven");
        // 指向一个不会被真正调用的地址 —— 这个测试只关心装配结果
        definition.setBaseUrl("http://127.0.0.1:9/v1");
        definition.setApiKeys(List.of("fake-key-1234567"));
        definition.setModels(List.of("cfg-model"));
        definition.setPriority(10);

        ProviderRegistry registry = new ProviderRegistry(
                List.of(new TestProvider("mock", List.of("mock-ok"), 1000)),
                factoryWith(definition));

        assertThat(registry.allModels()).containsExactlyInAnyOrder("mock-ok", "cfg-model");
    }

    @Test
    @DisplayName("缺少密钥的供应商配置会被跳过（而不是启动失败）")
    void unusableDefinitionIsSkipped() {
        ProviderProperties.Definition definition = new ProviderProperties.Definition();
        definition.setName("no-key");
        definition.setBaseUrl("http://127.0.0.1:9/v1");
        definition.setModels(List.of("cfg-model"));
        // 故意不配 api-key —— 真实场景就是环境变量没设置

        ProviderFactory factory = factoryWith(definition);

        assertThat(factory.createAll()).as("配置不完整应被跳过").isEmpty();
    }
}
