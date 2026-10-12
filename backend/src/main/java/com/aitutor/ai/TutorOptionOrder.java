package com.aitutor.ai;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.regex.Pattern;

/** 仅在新备课校验后重排；题目、答案和选项反馈作为同一组保存。 */
final class TutorOptionOrder {
    // 保守保留可能引用选项字母/相对位置的文本；不改写模型的自然语言或教材原文。
    // 单独的数学/代码变量 A-D 也会保留原序。此检查不等同于语义审阅。
    private static final Pattern POSITION_REFERENCE = Pattern.compile(
            "(?<![A-Za-z0-9_$])[A-D](?![A-Za-z0-9_$])"
            + "|(?:以上|以下|上述|下述|上面|下面|前面|后面).{0,6}(?:选项|答案|均|都|皆|全)"
            + "|(?:前|后|上|下)[两二三四2-4]项|第[一二三四1-4](?:个)?(?:选项|答案|项)"
            + "|(?:选项|答案)[一二三四1-4]|(?:全部|所有|其余|其他)(?:的)?(?:选项|答案)"
            + "|(?:两|三|四)者(?:都|均|皆)"
            + "|(?:选项|答案|选择|选|答)\\s*[a-d](?![A-Za-z0-9_$])|(?<![A-Za-z0-9_$])[a-d]\\s*(?:选项|项)"
            + "|(?i:\\b(?:all|none|both|neither)\\b.{0,20}\\b(?:above|below)\\b"
            + "|\\b(?:first|second|third|fourth|previous|next)\\s+(?:option|choice)s?\\b"
            + "|\\b(?:option|choice)s?\\s+[a-d1-4]\\b)");

    private TutorOptionOrder() {}

    static TutorLearningFlow.Lesson arrange(TutorLearningFlow.Lesson lesson, Random random) {
        if (referencesPosition(lesson.explanation()) || referencesPosition(lesson.remedialExplanation())) return lesson;
        var questions = new ArrayList<TutorLearningFlow.Question>();
        questions.add(lesson.diagnosis()); questions.addAll(lesson.checks()); questions.addAll(lesson.practices());
        // 相同选项数、可安全重排的题目作为一组；各答案位置的数量差最多为一。
        for (int size = 2; size <= 4; size++) {
            var indices = new ArrayList<Integer>();
            for (int i = 0; i < questions.size(); i++)
                if (questions.get(i).options().size() == size && reorderable(questions.get(i))) indices.add(i);
            if (indices.isEmpty()) continue;
            int offset = random.nextInt(size);
            var targets = new ArrayList<Integer>();
            for (int i = 0; i < indices.size(); i++) targets.add((i + offset) % size);
            Collections.shuffle(targets, random);
            for (int i = 0; i < indices.size(); i++) {
                int index = indices.get(i);
                questions.set(index, reorder(questions.get(index), targets.get(i), random));
            }
        }
        return new TutorLearningFlow.Lesson(lesson.explanation(), lesson.remedialExplanation(), questions.get(0),
                List.copyOf(questions.subList(1, 3)), List.copyOf(questions.subList(3, 5)));
    }

    private static boolean reorderable(TutorLearningFlow.Question q) {
        return !referencesPosition(q.prompt()) && !referencesPosition(q.explanation())
                && q.options().stream().noneMatch(TutorOptionOrder::referencesPosition)
                && q.wrongOptionFeedback().stream().noneMatch(f -> referencesPosition(f.explanation()));
    }

    private static boolean referencesPosition(String text) {
        return POSITION_REFERENCE.matcher(text).find();
    }

    private static TutorLearningFlow.Question reorder(TutorLearningFlow.Question q, int target, Random random) {
        int correct = q.correctOption().charAt(0) - 'A';
        var originalIndices = new ArrayList<Integer>();
        for (int i = 0; i < q.options().size(); i++) if (i != correct) originalIndices.add(i);
        Collections.shuffle(originalIndices, random);
        originalIndices.add(target, correct);
        var options = new ArrayList<String>();
        var feedback = new ArrayList<TutorLearningFlow.OptionFeedback>();
        for (int i = 0; i < originalIndices.size(); i++) {
            int original = originalIndices.get(i);
            options.add(q.options().get(original));
            if (original != correct) {
                var f = q.wrongOptionFeedback().stream().filter(item -> item.option().charAt(0) - 'A' == original)
                        .findFirst().orElseThrow();
                feedback.add(new TutorLearningFlow.OptionFeedback(label(i), f.focus(), f.explanation(), f.sourceQuote(), f.evidence()));
            }
        }
        return new TutorLearningFlow.Question(q.prompt(), List.copyOf(options), label(target), q.explanation(), List.copyOf(feedback));
    }

    private static String label(int index) { return String.valueOf((char) ('A' + index)); }
}
