package com.aitutor.ai;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import static com.aitutor.ai.TutorLearningFlow.*;

/** 优先选择检查同一区分点的未作答题，原文重合只用于同类题排序。 */
final class TutorQuestionSelector {
    static final String LEGACY_POLICY = "evidence_overlap_v1";
    static final String POLICY = "distinction_then_evidence_v2";
    record Choice(int index, QuestionSelection selection) {}

    static Choice choose(Lesson lesson, List<Evidence> answers, String stage) {
        return choose(policyFor(lesson), lesson, answers, stage);
    }

    static Choice choose(String policy, Lesson lesson, List<Evidence> answers, String stage) {
        List<Question> pool = "CHECKING".equals(stage) ? lesson.checks() : lesson.practices();
        var attempted = new HashSet<String>();
        Evidence lastWrong = null;
        for (Evidence answer : answers) {
            attempted.add(answer.question());
            if (!answer.correct()) lastWrong = answer;
        }
        List<SourceEvidence> target = List.of();
        String targetFocus = null;
        if (lastWrong != null) {
            var questions = new ArrayList<Question>();
            questions.add(lesson.diagnosis()); questions.addAll(lesson.checks()); questions.addAll(lesson.practices());
            for (Question question : questions) {
                if (!question.prompt().equals(lastWrong.question()) || question.wrongOptionFeedback() == null) continue;
                for (OptionFeedback feedback : question.wrongOptionFeedback()) {
                    if (!feedback.option().equals(lastWrong.selectedOption())) continue;
                    if (feedback.evidence() != null) target = feedback.evidence();
                    if (feedback.focus() != null && !feedback.focus().isBlank()) targetFocus = feedback.focus().trim();
                }
            }
        }
        int selected = -1;
        boolean selectedFocusMatch = false;
        List<String> shared = List.of();
        for (int index = 0; index < pool.size(); index++) {
            Question question = pool.get(index);
            if (attempted.contains(question.prompt())) continue;
            var references = new HashSet<SourceEvidence>();
            var focuses = new HashSet<String>();
            if (question.wrongOptionFeedback() != null) {
                for (OptionFeedback feedback : question.wrongOptionFeedback()) {
                    if (feedback.evidence() != null) references.addAll(feedback.evidence());
                    if (feedback.focus() != null && !feedback.focus().isBlank()) focuses.add(feedback.focus().trim());
                }
            }
            List<String> matched = target.stream().distinct().filter(references::contains).map(SourceEvidence::id).toList();
            boolean focusMatch = POLICY.equals(policy) && targetFocus != null && focuses.contains(targetFocus);
            if (POLICY.equals(policy) && !focusMatch) matched = List.of();
            // 同分保持备课顺序；编号与原文必须同时相同，不能仅凭编号关联。
            if (selected == -1 || focusMatch && !selectedFocusMatch
                    || focusMatch == selectedFocusMatch && matched.size() > shared.size()) {
                selected = index;
                selectedFocusMatch = focusMatch;
                shared = matched;
            }
        }
        if (selected == -1) return null;
        String reason;
        if (lastWrong == null) reason = "ORIGINAL_ORDER";
        else if (POLICY.equals(policy))
            reason = selectedFocusMatch ? "DISTINCTION_MATCH" : "NO_DISTINCTION_MATCH";
        else reason = shared.isEmpty() ? "NO_SHARED_EVIDENCE" : "SHARED_EVIDENCE";
        return new Choice(selected, new QuestionSelection(stage, pool.get(selected).prompt(), reason,
                lastWrong == null ? null : lastWrong.question(), lastWrong == null ? null : lastWrong.selectedOption(), shared));
    }

    static String policyFor(Lesson lesson) {
        var questions = new ArrayList<Question>();
        questions.add(lesson.diagnosis()); questions.addAll(lesson.checks()); questions.addAll(lesson.practices());
        boolean complete = questions.stream().allMatch(question -> question.wrongOptionFeedback() != null
                        && question.wrongOptionFeedback().size() == question.options().size() - 1)
                && questions.stream().flatMap(question -> question.wrongOptionFeedback().stream())
                .allMatch(feedback -> feedback.focus() != null && !feedback.focus().isBlank());
        return complete ? POLICY : LEGACY_POLICY;
    }

    static boolean supports(String policy) {
        return POLICY.equals(policy) || LEGACY_POLICY.equals(policy);
    }

    static String reason(QuestionSelection selection) {
        return switch (selection.reason()) {
            case "DISTINCTION_MATCH" -> "这道题检查与最近一次错误相同的区分点，并优先沿用相关原文。";
            case "NO_DISTINCTION_MATCH" -> "剩余题目中没有同一区分点的题，按原顺序继续检查。";
            case "SHARED_EVIDENCE" -> "这道题引用了最近一次补讲中的原文，先用它检查理解。";
            case "NO_SHARED_EVIDENCE" -> "剩余题目中没有找到引用相同原文的题，按原顺序继续检查。";
            default -> null;
        };
    }
}
