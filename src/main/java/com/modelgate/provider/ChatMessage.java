package com.modelgate.provider;

/**
 * 一条对话消息。与 OpenAI 的 messages 结构对齐。
 *
 * role 取值：system / user / assistant / tool
 * 用 String 而不是 enum，是为了在遇到新 role 时不会直接反序列化失败 ——
 * 上游协议是会演进的，网关不能因为多了一个没见过的 role 就整体挂掉。
 */
public record ChatMessage(String role, String content) {
}
