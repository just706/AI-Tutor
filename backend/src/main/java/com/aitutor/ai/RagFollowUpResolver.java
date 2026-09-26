package com.aitutor.ai;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Resolves references only; conversation text must never become textbook evidence. */
public final class RagFollowUpResolver {
    private static final Pattern REFERENCE = Pattern.compile(
            "[这那]两[种个](?:概念|对象|术语|方案|集合|结构|实现|方式|方法|类)?|前者|后者|两者|二者|(?<!其)(?:那)?它们?|[这那]个(?:概念|对象|术语|方案|类|集合)?");

    private RagFollowUpResolver() { }

    public static boolean needsContext(String question) {
        return REFERENCE.matcher(question).find();
    }

    public static Resolution resolve(String question, List<String> recentUserQuestions) {
        if (!needsContext(question)) return new Resolution(question, null);
        List<String> subjects = List.of();
        // 输入按新到旧排列。逐轮重建明确对象，避免“后者”之后的“它”退回整个候选对。
        for (int index = Math.min(recentUserQuestions.size(), 10) - 1; index >= 0; index--) {
            String previous = recentUserQuestions.get(index);
            if (!needsContext(previous)) {
                subjects = subjects(previous);
            } else if (!onlyReferences(previous)) {
                // 混合显式名称与未知指代不是一次可靠的话题切换。
                subjects = List.of();
            } else {
                Resolution resolved = replaceReferences(previous, subjects);
                if (resolved.clarification() == null) subjects = subjects(resolved.question());
            }
        }
        if (!onlyReferences(question)) return clarify(question, subjects);
        return replaceReferences(question, subjects);
    }

    private static Resolution replaceReferences(String question, List<String> subjects) {
        Matcher matcher = REFERENCE.matcher(question);
        StringBuilder rewritten = new StringBuilder();
        while (matcher.find()) {
            String reference = matcher.group();
            String replacement;
            if ("前者".equals(reference) || "后者".equals(reference)) {
                if (subjects.size() != 2) return clarify(question, subjects);
                replacement = subjects.get("前者".equals(reference) ? 0 : 1);
            } else if (reference.contains("两") || "二者".equals(reference) || reference.endsWith("它们")) {
                if (subjects.size() != 2) return clarify(question, subjects);
                replacement = String.join(" 和 ", subjects);
            } else {
                if (subjects.size() != 1) return clarify(question, subjects);
                replacement = subjects.get(0);
            }
            matcher.appendReplacement(rewritten, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(rewritten);
        return new Resolution(rewritten.toString(), null);
    }

    private static boolean onlyReferences(String question) {
        List<String> names = subjects(question);
        return !names.isEmpty() && names.stream().allMatch(name -> REFERENCE.matcher(name).matches());
    }

    private static List<String> subjects(String question) {
        return RagQuestionParser.parse(question).subjects();
    }

    private static Resolution clarify(String question, List<String> subjects) {
        String candidates = subjects.isEmpty() ? "" : "近期明确提到的对象有：" + String.join("、", subjects.stream().limit(4).toList()) + "。";
        return new Resolution(question, candidates + "请明确你指的是哪个对象；可以直接写出名称和问题。若要比较两种，请写出两个名称。");
    }

    public record Resolution(String question, String clarification) { }
}
