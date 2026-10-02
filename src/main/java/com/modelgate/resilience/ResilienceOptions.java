package com.modelgate.resilience;

/**
 * 单次调用的容错参数覆盖。
 *
 * 字段可空：null 表示"用配置文件里的默认值"。
 * 之所以允许覆盖，是为了让实验台能逐次对比不同参数的效果，
 * 而不用改配置重启。
 */
public record ResilienceOptions(Long timeoutMs, Integer maxAttempts, Boolean cancelOnTimeout) {

    public static final ResilienceOptions DEFAULT = new ResilienceOptions(null, null, null);

    public long timeoutOrDefault(long fallback) {
        return timeoutMs != null ? timeoutMs : fallback;
    }

    public int attemptsOrDefault(int fallback) {
        return maxAttempts != null ? maxAttempts : fallback;
    }

    public boolean cancelOrDefault(boolean fallback) {
        return cancelOnTimeout != null ? cancelOnTimeout : fallback;
    }
}
