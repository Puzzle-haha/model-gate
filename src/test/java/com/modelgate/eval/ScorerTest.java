package com.modelgate.eval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 打分器测试。
 *
 * 为什么值得单测：打分器决定了评测结果是否可信。
 * 归一化做少了，你测的是"模型有没有按格式输出"而不是模型能力；
 * 做多了，真正的错误会被判成对。两个方向的错误都不会报错，
 * 只会让你拿到一个看起来很专业但完全失真的准确率。
 */
class ScorerTest {

    private final Scorer scorer = new Scorer();

    private EvalTask exact(String expected) {
        return new EvalTask("t", "arithmetic", "prompt", expected, "exact_match");
    }

    private EvalTask contains(String expected) {
        return new EvalTask("t", "sentiment", "prompt", expected, "contains");
    }

    // ---------------------------------------------------------------- 应当判对

    @Test
    @DisplayName("完全一致判对")
    void exactMatch() {
        assertThat(scorer.score(exact("19"), "19")).isTrue();
    }

    @Test
    @DisplayName("首尾空白不影响判定")
    void trimsWhitespace() {
        assertThat(scorer.score(exact("19"), "  19  ")).isTrue();
        assertThat(scorer.score(exact("19"), "\n19\t")).isTrue();
    }

    @Test
    @DisplayName("句末标点不影响判定 —— 模型经常输出「19。」")
    void ignoresTrailingPunctuation() {
        assertThat(scorer.score(exact("19"), "19。")).isTrue();
        assertThat(scorer.score(exact("19"), "19.")).isTrue();
        assertThat(scorer.score(exact("负面"), "负面！")).isTrue();
        assertThat(scorer.score(exact("19"), "19，")).isTrue();
    }

    @Test
    @DisplayName("全角数字/字母能正确归一化")
    void normalizesFullWidthCharacters() {
        assertThat(scorer.score(exact("19"), "１９")).isTrue();
        assertThat(scorer.score(exact("abc"), "ＡＢＣ")).isTrue();
    }

    @Test
    @DisplayName("大小写不敏感")
    void caseInsensitive() {
        assertThat(scorer.score(exact("positive"), "POSITIVE")).isTrue();
    }

    @Test
    @DisplayName("去掉包裹的引号和书名号")
    void stripsQuotes() {
        assertThat(scorer.score(exact("正面"), "「正面」")).isTrue();
        assertThat(scorer.score(exact("正面"), "\"正面\"")).isTrue();
    }

    @Test
    @DisplayName("contains 模式：期望答案出现在输出里即可")
    void containsMode() {
        assertThat(scorer.score(contains("正面"), "这句话的情感是正面")).isTrue();
    }

    // ---------------------------------------------------------------- 必须判错

    @Test
    @DisplayName("答案错误必须判错 —— 不能被归一化掩盖")
    void wrongAnswerFails() {
        assertThat(scorer.score(exact("19"), "20")).isFalse();
        assertThat(scorer.score(exact("正面"), "负面")).isFalse();
    }

    @Test
    @DisplayName("答案被截断判错")
    void truncatedAnswerFails() {
        assertThat(scorer.score(exact("632"), "63")).isFalse();
        assertThat(scorer.score(exact("632"), "6")).isFalse();
    }

    @Test
    @DisplayName("空输出判错")
    void emptyOutputFails() {
        assertThat(scorer.score(exact("19"), "")).isFalse();
        assertThat(scorer.score(exact("19"), "   ")).isFalse();
        assertThat(scorer.score(exact("19"), null)).isFalse();
    }

    @Test
    @DisplayName("带解释的长输出在 exact_match 下判错 —— 这是刻意的边界")
    void longAnswerWithExplanationFailsExactMatch() {
        // 这看似"不公平"，但它是刻意的：如果任务要求"只输出数字"，
        // 多输出解释说明模型没有遵守指令，这本身就是要测的能力之一。
        // 真正的问题在于提示词设计，而不是打分器 —— 打分器放宽反而会掩盖问题。
        assertThat(scorer.score(exact("19"), "答案是 19")).isFalse();
    }

    @Test
    @DisplayName("contains 模式对错误答案仍然判错")
    void containsStillRejectsWrong() {
        assertThat(scorer.score(contains("正面"), "这句话的情感是负面")).isFalse();
    }

    @Test
    @DisplayName("归一化不会把「有空格」和「无空格」混为一谈（不能过度归一化）")
    void doesNotOverNormalize() {
        // "1 9" 和 "19" 是不同的字符串。如果归一化把中间空格也去掉，
        // 那就会把格式错误判成对 —— 过度归一化和归一化不足一样有害。
        assertThat(scorer.score(exact("19"), "1 9")).isFalse();
    }
}
