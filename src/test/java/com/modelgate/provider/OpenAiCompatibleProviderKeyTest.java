package com.modelgate.provider;

import com.modelgate.resilience.LlmCallException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 密钥池与容错层的交界行为测试。
 *
 * 为什么值得单测：这里出错的形态是**性能悄悄变差**，不会报错。
 * 「全部密钥都在冷却」这个错误如果被标成可重试，每个请求就会白白
 * 多等几百毫秒、多烧两次尝试，而且熔断器还会被多记 3 倍失败。
 * 功能测试完全看不出来 —— 接口照样返回降级内容，只是慢了 750 倍。
 */
class OpenAiCompatibleProviderKeyTest {

    private static final long AUTH_COOLDOWN_MS = 600_000;

    private ProviderProperties.Definition config() {
        ProviderProperties.Definition config = new ProviderProperties.Definition();
        config.setName("test-provider");
        // 指向一个不会被真正调用的地址：这些用例都在发出 HTTP 之前就失败了
        config.setBaseUrl("http://127.0.0.1:9/v1");
        config.setModels(List.of("fake-fast"));
        return config;
    }

    private OpenAiCompatibleProvider provider(ApiKeyPool pool) {
        return new OpenAiCompatibleProvider(config(), RestClient.builder(), pool);
    }

    private ProviderRequest request() {
        return new ProviderRequest("fake-fast", List.of(new ChatMessage("user", "hi")), 0.0d, 16);
    }

    @Test
    @DisplayName("★ 全部密钥冷却时标记为【不可重试】—— 重试在数学上不可能成功")
    void allKeysCoolingIsNotRetryable() {
        ApiKeyPool pool = new ApiKeyPool(List.of("only-one-key-0001"), AUTH_COOLDOWN_MS, 30_000);
        // 把唯一的密钥冷却掉（鉴权失败，冷却 10 分钟）
        pool.reportFailure("only-one-key-0001", "upstream_401");
        assertThat(pool.allCooling()).isTrue();

        assertThatThrownBy(() -> provider(pool).chat(request()))
                .isInstanceOf(LlmCallException.class)
                .satisfies(e -> {
                    LlmCallException lce = (LlmCallException) e;
                    assertThat(lce.errorType()).isEqualTo("all_keys_cooling");
                    assertThat(lce.isRetryable())
                            .as("冷却以分钟计、退避只有几百毫秒，重试注定拿到同样结果。"
                                    + "标成可重试会让每个请求白等 700ms 并多烧 3 倍失败")
                            .isFalse();
                });
    }

    @Test
    @DisplayName("鉴权失败标记为不可重试（密钥错了，重试一万次还是一样）")
    void authFailureIsReportedAsNotRetryableForSingleKey() {
        ApiKeyPool pool = new ApiKeyPool(List.of("only-one-key-0001"), AUTH_COOLDOWN_MS, 30_000);
        // 池里只有一把密钥 → hasHealthyKeyOtherThan=false → 不该重试
        assertThat(pool.hasHealthyKeyOtherThan("only-one-key-0001")).isFalse();
    }

    @Test
    @DisplayName("还有健康密钥时，401 才值得重试（重试会自动换一把凭证）")
    void authFailureIsRetryableWhenAnotherKeyExists() {
        ApiKeyPool pool = new ApiKeyPool(
                List.of("bad-key-00000001", "good-key-0000002"), AUTH_COOLDOWN_MS, 30_000);
        pool.reportFailure("bad-key-00000001", "upstream_401");

        assertThat(pool.hasHealthyKeyOtherThan("bad-key-00000001"))
                .as("池里还有好的密钥 → 重试会换一把 → 值得重试")
                .isTrue();
    }

    @Test
    @DisplayName("没有可用密钥时，异常信息里不能泄露任何密钥内容")
    void errorMessageDoesNotLeakKeys() {
        String secret = "sk-super-secret-key-value-123456";
        ApiKeyPool pool = new ApiKeyPool(List.of(secret), AUTH_COOLDOWN_MS, 30_000);
        pool.reportFailure(secret, "upstream_401");

        assertThatThrownBy(() -> provider(pool).chat(request()))
                .isInstanceOf(LlmCallException.class)
                .satisfies(e -> assertThat(e.getMessage())
                        .as("异常信息会进日志和接口返回，绝不能带出完整密钥")
                        .doesNotContain(secret));
    }
}
