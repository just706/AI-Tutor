package com.aitutor.ai;

import java.util.List;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Resolves references only; conversation text must never become textbook evidence. */
public final class RagFollowUpResolver {
    private static final Pattern REFERENCE = Pattern.compile(
            "(?:那两种|这两种|那两个|这两个)(?:集合|结构|实现|方式|方法|类)?|前者|后者|两者|二者|(?<!其)它们?|这个类|那个类|这个集合|那个集合");
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");
    private static final Set<String> ENGLISH_WORDS = Set.of(
            "what", "which", "how", "why", "the", "a", "an", "is", "are", "and", "or", "of", "in", "to",
            "for", "with", "please", "explain", "tell", "me", "about", "compare", "difference", "between", "vs");
    private static final List<String> CHINESE_TOPICS = List.of(
            "动态数组", "双向链表", "单向链表", "哈希表", "迭代器", "链表", "数组", "接口", "泛型", "继承", "多态", "封装", "反射", "异常", "线程", "集合");

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
            } else if (!explicitSubjects(previous).isEmpty()) {
                // 混合显式名称与未知指代不是一次可靠的话题切换。
                subjects = List.of();
            } else {
                Resolution resolved = replaceReferences(previous, subjects);
                if (resolved.clarification() == null) subjects = subjects(resolved.question());
            }
        }
        if (!explicitSubjects(question).isEmpty()) return clarify(question, subjects);
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
            } else if (reference.contains("两") || reference.contains("两个") || "二者".equals(reference) || "它们".equals(reference)) {
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

    private static List<String> explicitSubjects(String question) {
        // “那两种集合”里的“集合”属于指代本身，不是用户新增的对象。
        return subjects(REFERENCE.matcher(question).replaceAll(" "));
    }

    private static List<String> subjects(String question) {
        LinkedHashMap<String, String> identifiers = new LinkedHashMap<>();
        Matcher matcher = IDENTIFIER.matcher(question);
        while (matcher.find()) {
            String name = matcher.group();
            String key = name.toLowerCase(Locale.ROOT);
            if (name.length() > 1 && !ENGLISH_WORDS.contains(key)) identifiers.putIfAbsent(key, name);
        }
        if (identifiers.size() > 1) identifiers.remove("java");
        if (!identifiers.isEmpty()) return List.copyOf(identifiers.values());
        List<String> topics = new ArrayList<>();
        for (String topic : CHINESE_TOPICS) {
            if (question.contains(topic) && topics.stream().noneMatch(selected -> selected.contains(topic))) topics.add(topic);
        }
        topics.sort(Comparator.comparingInt(question::indexOf));
        return topics;
    }

    private static Resolution clarify(String question, List<String> subjects) {
        String candidates = subjects.isEmpty() ? "" : "近期明确提到的对象有：" + String.join("、", subjects.stream().limit(4).toList()) + "。";
        return new Resolution(question, candidates + "请明确你指的是哪个对象；可以直接写出名称和问题。若要比较两种，请写出两个名称。");
    }

    public record Resolution(String question, String clarification) { }
}
