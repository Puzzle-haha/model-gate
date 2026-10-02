package com.modelgate.eval;

import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * 打分器。
 *
 * ============================================================================
 * 归一化是打分正确性的前提
 * ============================================================================
 * 模型输出的 "19"、"19。"、" 19 "、"答案是 19" 在语义上是同一个答案，
 * 但直接字符串比较会把后三个判成错。
 *
 * 结果就是：**你测出来的不是模型能力，而是"模型有没有按你的格式要求输出"**。
 * 这个区别很关键 —— 前者是你要评估的东西，后者只是提示词工程的问题，
 * 混在一起会让评测结果完全失真。
 *
 * 所以每一步归一化都是刻意的：
 *   1. 去首尾空白
 *   2. 去掉常见的句末标点（。. ！! 等）
 *   3. 全角转半角（数字和字母）
 *   4. 大小写统一
 *
 * 但**不能过度归一化** —— 比如把「正面」和「负 面」也当成一样，
 * 那就把真正的错误也判成对了。归一化到什么程度，取决于任务的语义边界。
 */
@Component
public class Scorer {

    /**
     * 打分。
     *
     * @return 是否得分
     */
    public boolean score(EvalTask task, String actual) {
        if (actual == null) {
            return false;
        }
        String a = normalize(actual);
        String e = normalize(task.expected());

        return switch (task.scorerOrDefault()) {
            case "contains" -> a.contains(e);
            // 默认精确匹配
            default -> a.equals(e);
        };
    }

    /** 归一化：让"语义相同但格式不同"的输出能被正确判定。 */
    public String normalize(String s) {
        if (s == null) {
            return "";
        }
        String t = s.trim();
        // 全角数字/字母 -> 半角（模型经常输出全角字符）
        StringBuilder sb = new StringBuilder(t.length());
        for (char c : t.toCharArray()) {
            if (c >= '０' && c <= '９') {
                sb.append((char) (c - '０' + '0'));
            } else if (c >= 'Ａ' && c <= 'ｚ') {
                sb.append((char) (c - 'Ａ' + 'A'));
            } else {
                sb.append(c);
            }
        }
        t = sb.toString();
        // 去掉句末标点（可能叠加多个）
        t = t.replaceAll("[。.！!？?，,、;；:：\\s]+$", "");
        t = t.replaceAll("^[\\s\"'「『]+", "").replaceAll("[\\s\"'」』]+$", "");
        return t.toLowerCase(Locale.ROOT).trim();
    }
}
