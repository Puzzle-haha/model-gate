package com.modelgate.provider;

import java.util.List;

/**
 * 网关内部的统一请求模型。
 *
 * 为什么要有它，而不是直接把 OpenAI 的 DTO 传给 Provider：
 * 各家供应商的协议不一样（OpenAI / Anthropic / 通义 / 智谱…）。
 * 如果 Provider 接口直接吃 OpenAI 的 DTO，那每接一家新供应商，
 * 适配代码就会散落到控制器里。
 *
 * 统一模型的正确位置是【适配层的输入契约】：
 *
 *   OpenAI 请求 ──► 统一请求模型 ──► [各 Provider 适配] ──► 各家 HTTP 协议
 *
 * 这样加一家新供应商只需要写一个 Provider 实现，不用动控制器。
 */
public record ProviderRequest(
        String model,
        List<ChatMessage> messages,
        Double temperature,
        Integer maxTokens) {
}
