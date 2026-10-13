package com.aitutor.ai;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import static com.aitutor.ai.TutorLearningFlow.*;

/** 按补讲引用选择未作答题；资料重合只是关联线索，不代表已经识别学生错因。 */
final class TutorQuestionSelector {
    static final String POLICY = "evidence_overlap_v1";
    record Choice(int index, QuestionSelection selection) {}

    static Choice choose(Lesson lesson, List<Evidence> answers, String stage) {
        List<Question> pool = "CHECKING".equals(stage) ? lesson.checks() : lesson.practices();
        var attempted = new HashSet<String>();
        Evidence lastWrong = null;
        for (Evidence answer : answers) {
            attempted.add(answer.question());
            if (!answer.correct()) lastWrong = answer;
        }
        List<SourceEvidence> target = List.of();
        if (lastWrong != null) {
            var questions = new ArrayList<Question>();
            questions.add(lesson.diagnosis()); questions.addAll(lesson.checks()); questions.addAll(lesson.practices());
            for (Question question : questions) {
                if (!question.prompt().equals(lastWrong.question()) || question.wrongOptionFeedback() == null) continue;
                for (OptionFeedback feedback : question.wrongOptionFeedback()) {
                    if (feedback.option().equals(lastWrong.selectedOption()) && feedback.evidence() != null)
                        target = feedback.evidence();
                }
            }
        }
        int selected = -1;
        List<String> shared = List.of();
        for (int index = 0; index < pool.size(); index++) {
            Question question = pool.get(index);
            if (attempted.contains(question.prompt())) continue;
            var references = new HashSet<SourceEvidence>();
            if (question.wrongOptionFeedback() != null) {
                for (OptionFeedback feedback : question.wrongOptionFeedback())
                    if (feedback.evidence() != null) references.addAll(feedback.evidence());
            }
            List<String> matched = target.stream().distinct().filter(references::contains).map(SourceEvidence::id).toList();
            // 同分保持备课顺序；编号与原文必须同时相同，不能仅凭编号关联。
            if (selected == -1 || matched.size() > shared.size()) {
                selected = index;
                shared = matched;
            }
        }
        if (selected == -1) return null;
        String reason = lastWrong == null ? "ORIGINAL_ORDER" : shared.isEmpty() ? "NO_SHARED_EVIDENCE" : "SHARED_EVIDENCE";
        return new Choice(selected, new QuestionSelection(stage, pool.get(selected).prompt(), reason,
                lastWrong == null ? null : lastWrong.question(), lastWrong == null ? null : lastWrong.selectedOption(), shared));
    }

    static String reason(QuestionSelection selection) {
        return switch (selection.reason()) {
            case "SHARED_EVIDENCE" -> "这道题引用了最近一次补讲中的原文，先用它检查理解。";
            case "NO_SHARED_EVIDENCE" -> "剩余题目中没有找到引用相同原文的题，按原顺序继续检查。";
            default -> null;
        };
    }
}
