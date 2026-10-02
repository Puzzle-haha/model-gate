package com.modelgate.eval;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 一条评测任务。
 *
 * 设计原则：**任务必须有唯一、可自动判定的正确答案。**
 *
 * 为什么这条原则很重要：如果评测需要人来判断"这个回答好不好"，
 * 那评测就跑不快、不可重复，而且换个人评结果就变了。
 * 那样得到的分数没有任何决策价值 —— 你没法用它来说
 * "模型 A 比 B 好"，只能说"我感觉 A 好一点"。
 *
 * 真实项目里当然也会有主观任务（写作、总结）。那些用
 * 人工标注的小样本 + LLM-as-judge 来评，但那是另一个层次的问题。
 * 先把"客观题"的评测闭环跑通，才有资格谈主观评测。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EvalTask(
        String id,
        /** 任务类型，用于分组统计（算术 / 情感 …）。 */
        String type,
        String prompt,
        /** 期望答案。 */
        String expected,
        /** 打分方式：exact_match / contains。缺省为 exact_match。 */
        String scorer) {

    public String scorerOrDefault() {
        return scorer == null || scorer.isBlank() ? "exact_match" : scorer;
    }
}
