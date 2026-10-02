package com.modelgate.cache;

import com.modelgate.provider.ProviderResponse;

/**
 * 缓存里存的一条响应。
 *
 * 只存重建响应所必需的字段，不存原始 JSON —— 原始响应体可能很大，
 * 而且格式随供应商变化，存进去反而增加了解析负担。
 *
 * 命中缓存时 token 用量照常上报（promptTokens / completionTokens），
 * 这样"按 token 计费"的账不会因为走了缓存就丢数据 ——
 * 但成本要记 0，因为这次没有真正调用上游。这个区分很关键，
 * 否则你会看到"token 用了很多但账单为 0"，以为是 bug。
 */
public record CachedResponse(
        String content,
        String model,
        Integer promptTokens,
        Integer completionTokens,
        String finishReason) {

    public static CachedResponse from(ProviderResponse response, String fallbackModel) {
        if (response == null) {
            return null;
        }
        return new CachedResponse(
                response.content(),
                response.model() != null ? response.model() : fallbackModel,
                response.promptTokens(),
                response.completionTokens(),
                response.finishReason());
    }

    public ProviderResponse toProviderResponse() {
        return new ProviderResponse(content, model, promptTokens, completionTokens, finishReason);
    }

    public boolean isUsable() {
        // 空内容不值得缓存：缓存了也只是把"无效结果"快速返回，
        // 反而让上游失去了重试纠正的机会
        return content != null && !content.isBlank();
    }
}
