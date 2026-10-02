package com.modelgate.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.modelgate.provider.ChatMessage;
import com.modelgate.provider.ProviderRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 缓存键构造测试 —— **本项目最重要的一个单测。**
 *
 * ============================================================================
 * 它守护的是一个真实发生过的 bug
 * ============================================================================
 * 最直觉的写法是用分隔符把字段拼起来：
 *
 *     "msg:" + role + ":" + content + "\n"
 *
 * 这个写法有 bug。考虑两组不同的请求：
 *
 *   A: messages = [("user", "a\nmsg:assistant:b")]
 *   B: messages = [("user", "a"), ("assistant", "b")]
 *
 * 两者拼出来的字符串完全相同：
 *
 *     "msg:user:a\nmsg:assistant:b\n"
 *
 * 于是**两个不同的请求命中同一个缓存，B 会拿到 A 的答案**。
 * prompt 里带换行太常见了，所以这不是理论问题。
 *
 * 修法是**长度前缀**（`字段名[长度]:内容`），从语法上消除歧义。
 *
 * 这个 bug 的危险性在于：它不会报错、不会打日志，只在特定输入下
 * 悄悄返回错误的数据。**没有这个测试，重构时极易改回去。**
 */
class ResponseCacheKeyTest {

    private CacheProperties props() {
        CacheProperties props = new CacheProperties();
        props.setKeyPrefix("mg:cache:");
        props.setIncludeTenant(true);
        return props;
    }

    private ResponseCache cache(CacheProperties props) {
        // keyFor() 完全不碰 Redis，所以这里传一个未连接任何实例的 StringRedisTemplate
        // 就能测键构造 —— 不需要启动 Redis，也不需要 Mockito。
        // 不用 mock 的好处：测试不依赖 mock 框架的行为，构建也不会打 agent 警告。
        return new ResponseCache(new StringRedisTemplate(), props, new ObjectMapper());
    }

    private ProviderRequest request(Double temperature, ChatMessage... messages) {
        return new ProviderRequest("fake-fast", List.of(messages), temperature, 256);
    }

    // ================================================================
    //  核心：一一映射（这个测试就是那个 bug 的回归测试）
    // ================================================================

    @Test
    @DisplayName("★ 不同消息切分方式绝不能碰撞到同一个键")
    void differentMessageSplitsMustNotCollide() {
        ResponseCache cache = cache(props());

        // A：单条消息，内容里带换行和 "msg:assistant:"
        ProviderRequest a = request(0.0d,
                new ChatMessage("user", "a\nmsg:assistant:b"));

        // B：两条消息
        ProviderRequest b = request(0.0d,
                new ChatMessage("user", "a"),
                new ChatMessage("assistant", "b"));

        String keyA = cache.keyFor(a, "tenant");
        String keyB = cache.keyFor(b, "tenant");

        assertThat(keyA)
                .as("朴素的分隔符拼接会让这两个请求产生相同的键，"
                        + "导致 B 拿到 A 的缓存答案。长度前缀必须避免这一点")
                .isNotEqualTo(keyB);
    }

    @Test
    @DisplayName("★ 角色名里带分隔符也不能碰撞")
    void roleContainingSeparatorMustNotCollide() {
        ResponseCache cache = cache(props());

        ProviderRequest a = request(0.0d,
                new ChatMessage("user:assistant", "x"));
        ProviderRequest b = request(0.0d,
                new ChatMessage("user", "assistant:x"));

        assertThat(cache.keyFor(a, "t")).isNotEqualTo(cache.keyFor(b, "t"));
    }

    @Test
    @DisplayName("★ 内容里的换行位置不同不能碰撞")
    void newlinePositionMatters() {
        ResponseCache cache = cache(props());

        ProviderRequest a = request(0.0d, new ChatMessage("user", "line1\nline2"));
        ProviderRequest b = request(0.0d, new ChatMessage("user", "line1line2"));

        assertThat(cache.keyFor(a, "t")).isNotEqualTo(cache.keyFor(b, "t"));
    }

    @Test
    @DisplayName("消息顺序不同必须产生不同的键")
    void messageOrderMatters() {
        ResponseCache cache = cache(props());

        ProviderRequest a = request(0.0d,
                new ChatMessage("user", "1"),
                new ChatMessage("assistant", "2"));
        ProviderRequest b = request(0.0d,
                new ChatMessage("assistant", "2"),
                new ChatMessage("user", "1"));

        assertThat(cache.keyFor(a, "t")).isNotEqualTo(cache.keyFor(b, "t"));
    }

    // ================================================================
    //  确定性：同样的请求必须产生同样的键，否则缓存永远不命中
    // ================================================================

    @Test
    @DisplayName("相同请求产生相同键（否则缓存毫无意义）")
    void identicalRequestIsDeterministic() {
        ResponseCache cache = cache(props());

        ProviderRequest r1 = request(0.0d, new ChatMessage("user", "你好"));
        ProviderRequest r2 = request(0.0d, new ChatMessage("user", "你好"));

        assertThat(cache.keyFor(r1, "t")).isEqualTo(cache.keyFor(r2, "t"));
        // 且键前缀正确
        assertThat(cache.keyFor(r1, "t")).startsWith("mg:cache:");
    }

    // ================================================================
    //  所有影响输出的参数都必须进键
    // ================================================================

    @Test
    @DisplayName("温度不同则键不同 —— 否则会拿温度的缓存回答另一个请求")
    void temperatureIsPartOfKey() {
        ResponseCache cache = cache(props());

        assertThat(cache.keyFor(request(0.0d, new ChatMessage("user", "x")), "t"))
                .isNotEqualTo(cache.keyFor(request(0.7d, new ChatMessage("user", "x")), "t"));
    }

    @Test
    @DisplayName("maxTokens 不同则键不同")
    void maxTokensIsPartOfKey() {
        ResponseCache cache = cache(props());

        ProviderRequest a = new ProviderRequest("m", List.of(new ChatMessage("user", "x")), 0.0d, 256);
        ProviderRequest b = new ProviderRequest("m", List.of(new ChatMessage("user", "x")), 0.0d, 512);

        assertThat(cache.keyFor(a, "t")).isNotEqualTo(cache.keyFor(b, "t"));
    }

    @Test
    @DisplayName("模型不同则键不同")
    void modelIsPartOfKey() {
        ResponseCache cache = cache(props());

        ProviderRequest a = new ProviderRequest("model-a",
                List.of(new ChatMessage("user", "x")), 0.0d, 256);
        ProviderRequest b = new ProviderRequest("model-b",
                List.of(new ChatMessage("user", "x")), 0.0d, 256);

        assertThat(cache.keyFor(a, "t")).isNotEqualTo(cache.keyFor(b, "t"));
    }

    // ================================================================
    //  租户隔离
    // ================================================================

    @Test
    @DisplayName("includeTenant=true 时，不同调用方不会共享缓存")
    void tenantIsolatedWhenEnabled() {
        ResponseCache cache = cache(props());
        ProviderRequest r = request(0.0d, new ChatMessage("user", "x"));

        assertThat(cache.keyFor(r, "tenant-a")).isNotEqualTo(cache.keyFor(r, "tenant-b"));
    }

    @Test
    @DisplayName("includeTenant=false 时，不同调用方共享缓存（命中率换隔离性）")
    void tenantSharedWhenDisabled() {
        CacheProperties props = props();
        props.setIncludeTenant(false);
        ResponseCache cache = cache(props);
        ProviderRequest r = request(0.0d, new ChatMessage("user", "x"));

        assertThat(cache.keyFor(r, "tenant-a")).isEqualTo(cache.keyFor(r, "tenant-b"));
    }

    @Test
    @DisplayName("租户为 null 时用 anonymous 占位，不能与空字符串混淆")
    void nullTenantBecomesAnonymous() {
        ResponseCache cache = cache(props());
        ProviderRequest r = request(0.0d, new ChatMessage("user", "x"));

        assertThat(cache.keyFor(r, null)).isEqualTo(cache.keyFor(r, null));
        // 长度前缀保证 "anonymous" 和真正叫 "anonymous" 的租户不冲突（同值，无歧义）
        assertThat(cache.keyFor(r, null)).isNotEqualTo(cache.keyFor(r, ""));
    }

    // ================================================================
    //  可缓存性判定
    // ================================================================

    @Test
    @DisplayName("temperature=0 才可缓存")
    void onlyDeterministicIsCacheable() {
        ResponseCache cache = cache(props());
        ChatMessage msg = new ChatMessage("user", "x");

        assertThat(cache.isCacheable(request(0.0d, msg))).isTrue();
        assertThat(cache.isCacheable(request(0.7d, msg))).isFalse();
        assertThat(cache.isCacheable(request(1.0d, msg))).isFalse();
    }

    @Test
    @DisplayName("temperature 未指定时不可缓存 —— 那时用的是供应商默认值（通常 1.0）")
    void nullTemperatureIsNotCacheable() {
        ResponseCache cache = cache(props());

        assertThat(cache.isCacheable(request(null, new ChatMessage("user", "x")))).isFalse();
    }

    @Test
    @DisplayName("缓存关闭时一律不可缓存")
    void disabledCacheIsNeverCacheable() {
        CacheProperties props = props();
        props.setEnabled(false);
        ResponseCache cache = cache(props);

        assertThat(cache.isCacheable(request(0.0d, new ChatMessage("user", "x")))).isFalse();
    }

    @Test
    @DisplayName("关掉 onlyDeterministic 后非确定性请求也可缓存（危险开关，但要能工作）")
    void onlyDeterministicCanBeDisabled() {
        CacheProperties props = props();
        props.setOnlyDeterministic(false);
        ResponseCache cache = cache(props);

        assertThat(cache.isCacheable(request(0.7d, new ChatMessage("user", "x")))).isTrue();
    }

    // ================================================================
    //  单飞（防击穿）
    // ================================================================

    @Test
    @DisplayName("单飞：只有第一个请求拿到加载权，其余被合并")
    void singleFlightOnlyOneLoader() {
        ResponseCache cache = cache(props());
        String key = "mg:cache:single-flight-test";

        assertThat(cache.tryBeginLoad(key)).as("第一个请求应拿到加载权").isTrue();
        assertThat(cache.tryBeginLoad(key)).as("后续请求应被合并").isFalse();
        assertThat(cache.tryBeginLoad(key)).isFalse();
    }

    @Test
    @DisplayName("单飞：加载进行中的等待者能拿到加载结果")
    void singleFlightSharesResult() throws Exception {
        ResponseCache cache = cache(props());
        String key = "mg:cache:share-test";
        CachedResponse value = new CachedResponse("内容", "m", 1, 2, "stop");

        // 第一个请求拿到加载权，但先不完成
        assertThat(cache.tryBeginLoad(key)).isTrue();

        // 另一个线程作为等待者，会阻塞在 awaitLoad 上
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<CachedResponse> waiter = pool.submit(() -> cache.awaitLoad(key, 3000));
            Thread.sleep(150);   // 确保等待者已经进入 awaitLoad

            // 加载完成 —— 等待者应被唤醒并拿到同一个结果
            cache.finishLoad(key, value);

            assertThat(waiter.get(3, TimeUnit.SECONDS))
                    .as("等待者应当拿到加载方的结果，而不是自己去打上游")
                    .isEqualTo(value);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("finishLoad 之后再 awaitLoad 会回落到查缓存，且不会永久阻塞")
    void awaitAfterFinishFallsBackToCacheWithoutHanging() {
        ResponseCache cache = cache(props());
        String key = "mg:cache:after-finish";

        cache.tryBeginLoad(key);
        cache.finishLoad(key, new CachedResponse("内容", "m", 1, 1, "stop"));

        long t0 = System.currentTimeMillis();
        CachedResponse result = cache.awaitLoad(key, 200);
        long elapsed = System.currentTimeMillis() - t0;

        // 这是设计使然：加载方一定【先写缓存、再 finishLoad】，
        // 所以 finishLoad 之后到达的等待者会去查缓存而不是等在原地。
        // 这个测试用的是未连接 Redis 的 template，所以查缓存返回 null ——
        // 关键断言是"快速返回 null"而不是"挂住"：
        // 拿不到就自己去加载，绝不无限等待。
        assertThat(result).isNull();
        assertThat(elapsed).as("必须快速返回，不能阻塞").isLessThan(1500);
    }

    @Test
    @DisplayName("加载失败（传 null）后等待者拿到 null，且不会永久阻塞")
    void singleFlightHandlesFailure() {
        ResponseCache cache = cache(props());
        String key = "mg:cache:fail-test";

        assertThat(cache.tryBeginLoad(key)).isTrue();
        cache.finishLoad(key, null);

        assertThat(cache.awaitLoad(key, 100)).isNull();
    }

    @Test
    @DisplayName("finishLoad 是幂等的 —— 重复调用不能抛异常")
    void finishLoadIsIdempotent() {
        ResponseCache cache = cache(props());
        String key = "mg:cache:idempotent";

        cache.tryBeginLoad(key);
        cache.finishLoad(key, null);
        cache.finishLoad(key, null);   // 必须无害：finally 里可能重复调用

        assertThat(cache.tryBeginLoad(key)).as("锁应已释放，可以被再次获取").isTrue();
    }
}
