package com.modelgate.provider;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 密钥池测试。
 *
 * 为什么值得单测：
 *   1. **脱敏一旦回归就是密钥泄露** —— 这属于安全断言，必须锁死
 *   2. 冷却逻辑错了会导致"永远只用一把密钥"或"永远避开好密钥"，
 *      表现为限流频繁或可用配额莫名下降，都很难排查
 */
class ApiKeyPoolTest {

    private static final long AUTH_COOLDOWN = 60_000;
    private static final long RATE_COOLDOWN = 5_000;

    private ApiKeyPool pool(String... keys) {
        return new ApiKeyPool(List.of(keys), AUTH_COOLDOWN, RATE_COOLDOWN);
    }

    // ---------------------------------------------------------------- 脱敏（安全）

    @Test
    @DisplayName("长密钥脱敏：只留头尾各 4 位")
    void masksLongKeys() {
        assertThat(ApiKeyPool.mask("fake-key-for-local-test")).isEqualTo("fake…test");
        assertThat(ApiKeyPool.mask("sk-1234567890abcdef")).isEqualTo("sk-1…cdef");
    }

    @Test
    @DisplayName("短密钥整体遮蔽 —— 太短的话留头尾等于没遮")
    void masksShortKeysEntirely() {
        assertThat(ApiKeyPool.mask("fake-key-bad")).isEqualTo("****");   // 12 字符
        assertThat(ApiKeyPool.mask("short")).isEqualTo("****");
        assertThat(ApiKeyPool.mask("")).isEqualTo("(空)");
        assertThat(ApiKeyPool.mask(null)).isEqualTo("(空)");
    }

    @Test
    @DisplayName("stats() 暴露的密钥必须是脱敏后的")
    void statsNeverExposeRawKeys() {
        ApiKeyPool pool = pool("sk-1234567890abcdef");

        String rendered = pool.stats().toString();

        assertThat(rendered).doesNotContain("sk-1234567890abcdef");
        assertThat(rendered).contains("sk-1…cdef");
    }

    // ---------------------------------------------------------------- 轮询

    @Test
    @DisplayName("轮询使用多把密钥，不会只盯着一把")
    void rotatesAcrossKeys() {
        ApiKeyPool pool = pool("key-aaaaaaaaaa", "key-bbbbbbbbbb", "key-cccccccccc");

        String first = pool.acquire();
        String second = pool.acquire();
        String third = pool.acquire();

        assertThat(List.of(first, second, third)).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("重复配置同一把密钥会被去重 —— 否则轮询失去意义")
    void deduplicatesKeys() {
        ApiKeyPool pool = pool("same-key-123456", "same-key-123456", "same-key-123456");

        assertThat(pool.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("忽略空白密钥")
    void ignoresBlankKeys() {
        ApiKeyPool pool = pool("key-aaaaaaaaaa", "  ", "", "key-bbbbbbbbbb");

        assertThat(pool.size()).isEqualTo(2);
    }

    // ---------------------------------------------------------------- 冷却

    @Test
    @DisplayName("鉴权失败后该密钥进入冷却，不再被取用")
    void authFailureCoolsKey() {
        ApiKeyPool pool = pool("key-aaaaaaaaaa", "key-bbbbbbbbbb");

        String first = pool.acquire();
        pool.reportFailure(first, "upstream_401");

        // 冷却期内反复取用，必须永远拿到另一把
        for (int i = 0; i < 10; i++) {
            assertThat(pool.acquire()).isNotEqualTo(first);
        }
    }

    @Test
    @DisplayName("5xx 不该冷却密钥 —— 那是上游整体问题，冷却会把所有密钥一起误伤")
    void serverErrorDoesNotCoolKey() {
        ApiKeyPool pool = pool("key-aaaaaaaaaa", "key-bbbbbbbbbb");

        String key = pool.acquire();
        pool.reportFailure(key, "upstream_5xx");

        assertThat(coolingCount(pool)).isZero();
    }

    @Test
    @DisplayName("限流会冷却密钥（较短），因为限流通常是按密钥计的")
    void rateLimitCoolsKeyBriefly() {
        ApiKeyPool pool = pool("key-aaaaaaaaaa", "key-bbbbbbbbbb");

        String key = pool.acquire();
        pool.reportFailure(key, "rate_limited");

        assertThat(pool.allCooling()).isFalse();
        assertThat(coolingCount(pool)).isEqualTo(1);
    }

    @Test
    @DisplayName("全部密钥冷却中时 acquire 返回 null")
    void returnsNullWhenAllCooling() {
        ApiKeyPool pool = pool("key-aaaaaaaaaa", "key-bbbbbbbbbb");

        String a = pool.acquire();
        String b = pool.acquire();
        pool.reportFailure(a, "upstream_401");
        pool.reportFailure(b, "upstream_401");

        assertThat(pool.allCooling()).isTrue();
        assertThat(pool.acquire()).isNull();
    }

    @Test
    @DisplayName("成功会立刻解除冷却")
    void successClearsCooldown() {
        ApiKeyPool pool = pool("key-aaaaaaaaaa", "key-bbbbbbbbbb");

        String key = pool.acquire();
        pool.reportFailure(key, "rate_limited");
        assertThat(coolingCount(pool)).isEqualTo(1);

        pool.reportSuccess(key);
        assertThat(coolingCount(pool)).isZero();
    }

    // ---------------------------------------------------------------- hasHealthyKeyOtherThan

    @Test
    @DisplayName("还有别的健康密钥时返回 true —— 这决定了 401 要不要重试")
    void hasHealthyKeyOtherThanTrue() {
        ApiKeyPool pool = pool("key-aaaaaaaaaa", "key-bbbbbbbbbb");

        String key = pool.acquire();
        assertThat(pool.hasHealthyKeyOtherThan(key)).isTrue();
    }

    @Test
    @DisplayName("只有一把密钥时返回 false —— 401 重试毫无意义，还是那把")
    void hasHealthyKeyOtherThanFalseForSingleKey() {
        ApiKeyPool pool = pool("key-aaaaaaaaaa");

        String key = pool.acquire();
        assertThat(pool.hasHealthyKeyOtherThan(key)).isFalse();
    }

    @Test
    @DisplayName("其余密钥都在冷却时返回 false")
    void otherKeysCoolingMeansFalse() {
        ApiKeyPool pool = pool("key-aaaaaaaaaa", "key-bbbbbbbbbb");

        String a = pool.acquire();
        String b = pool.acquire();
        pool.reportFailure(b, "upstream_401");

        assertThat(pool.hasHealthyKeyOtherThan(a)).isFalse();
    }

    // ---------------------------------------------------------------- 辅助

    private long coolingCount(ApiKeyPool pool) {
        return pool.stats().stream().filter(m -> Boolean.FALSE.equals(m.get("healthy"))).count();
    }
}
