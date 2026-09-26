package com.aitutor.ai;

import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Conservative question grammar, with no dictionary of disciplines or concept names. */
final class RagQuestionParser {
    private static final Pattern INTRODUCTION = Pattern.compile(
            "^(?:(?:什么是|何谓|介绍|解释|讲解|我指的是|我说的是|我想学习|我想了解)\\s*(?:一下\\s*)?|(?:what\\s+(?:is|are)|explain|introduce|tell\\s+me\\s+about)\\s+)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern COMPARISON = Pattern.compile(
            "^(.+?)(?:有什么|有何|的)?(?:区别|差异|差别|异同|不同|相同点)(?:是什么|有哪些|在哪里|呢)?$");
    private static final Pattern FOCUS = Pattern.compile("^(.+?)(的|在|有什么|有何|是什么|如何|怎么样|怎么)(.*)$");
    private static final Pattern SEPARATOR = Pattern.compile("\\s*(?:和|与|及|、|\\band\\b|\\bversus\\b|\\bvs\\b)\\s*", Pattern.CASE_INSENSITIVE);
    private static final Pattern NAME = Pattern.compile("[\\p{L}\\p{N}_$+.#'’\\- ]{1,80}");
    private static final Pattern QUESTION_OR_CLAUSE = Pattern.compile(
            "为什么|什么|怎么|如何|多少|哪里|是否|能否|应该|需要|可以|经常|方面|[的在吗呢]|^(?:今天|明天|昨天|现在|接下来|先|再|我|你)|\\b(?:how|why|does|do|should|can|please)\\b",
            Pattern.CASE_INSENSITIVE);

    private RagQuestionParser() { }

    static Question parse(String question) {
        String text = question.trim().replaceFirst("[？?！!。]+$", "").trim()
                .replaceFirst("^(?:请问|请)\\s*", "");
        // 多分句可能切换话题或同时提出多个任务，不能把截取的半句当作确定对象。
        if (text.matches("(?s).*[，,。；;:：\\r\\n!?！？].*")) return new Question(List.of(), text, false);
        Matcher comparison = COMPARISON.matcher(text);
        if (comparison.matches()) {
            List<String> pair = names(comparison.group(1));
            if (pair.size() == 2) return new Question(pair, "", true);
        }
        if (text.startsWith("比较") || text.startsWith("对比")) {
            List<String> pair = names(text.substring(2));
            if (pair.size() == 2) return new Question(pair, "", true);
        }
        text = INTRODUCTION.matcher(text).replaceFirst("").trim();
        Matcher focus = FOCUS.matcher(text);
        if (focus.matches()) return new Question(names(focus.group(1)), focus.group(2) + focus.group(3), false);
        return new Question(names(text), "", false);
    }

    private static List<String> names(String phrase) {
        LinkedHashMap<String, String> names = new LinkedHashMap<>();
        for (String part : SEPARATOR.split(phrase, -1)) {
            String name = part.trim().replaceFirst("^[“‘\"]", "").replaceFirst("[”’\"]$", "").trim();
            if (!NAME.matcher(name).matches() || QUESTION_OR_CLAUSE.matcher(name).find()
                    || name.codePoints().noneMatch(Character::isLetter)) return List.of();
            names.putIfAbsent(normalize(name), name);
        }
        return List.copyOf(names.values());
    }

    static boolean containsName(String text, String name) {
        // 英文名称使用完整边界，防止把 Map 当作 HashMap；中文名称保留完整短语。
        return Pattern.compile("(?<![a-z0-9_$])" + Pattern.quote(normalize(name)) + "(?![a-z0-9_$])")
                .matcher(normalize(text)).find();
    }

    private static String normalize(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    record Question(List<String> subjects, String focus, boolean comparison) { }
}
