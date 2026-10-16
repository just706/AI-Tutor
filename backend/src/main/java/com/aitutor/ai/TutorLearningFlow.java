package com.aitutor.ai;

import java.util.List;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;
import com.aitutor.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.aitutor.vo.RagSourceVO;

/** 循序学习的状态与题目；正确答案只保存在服务端。 */
public final class TutorLearningFlow {
    public record SourceEvidence(String id, String text) {}
    public record OptionFeedback(String option, String focus, String explanation, String sourceQuote,
                                 List<SourceEvidence> evidence) {
        public OptionFeedback(String option, String focus, String explanation, String sourceQuote) {
            this(option,focus,explanation,sourceQuote,null);
        }
    }
    public record Question(String prompt, List<String> options, String correctOption, String explanation,
                           List<OptionFeedback> wrongOptionFeedback) {
        public Question(String prompt, List<String> options, String correctOption, String explanation) {
            this(prompt, options, correctOption, explanation, null);
        }
    }
    public record Lesson(String explanation, String remedialExplanation, Question diagnosis,
                         List<Question> checks, List<Question> practices) {}
    public record Evidence(String stage, String question, String selectedOption, boolean correct) {}
    public record Materials(List<Long> documentIds, List<RagSourceVO> sources) {}
    public record QuestionSelection(String stage, String question, String reason, String basedOnQuestion,
                                    String selectedOption, List<String> sharedEvidenceIds) {}
    public record SelectionState(String policy, List<QuestionSelection> history) {}
    public record State(int version, String stage, String topic, String source, Lesson lesson,
                        int checkIndex, int practiceIndex, List<Evidence> evidence, String feedback, Materials materials,
                        SelectionState selection) {
        public State(int version, String stage, String topic, String source, Lesson lesson,
                     int checkIndex, int practiceIndex, List<Evidence> evidence, String feedback, Materials materials) {
            this(version, stage, topic, source, lesson, checkIndex, practiceIndex, evidence, feedback, materials, null);
        }
    }
    public record View(int version, String stage, String topic, String prompt, List<String> options,
                       boolean canContinue, boolean passed, String feedback, boolean textbookBased, String selectionReason) {}

    private static final ObjectMapper JSON = new ObjectMapper();

    public static State clarify(String topic) {
        requireText(topic, 200);
        return new State(1, "CLARIFYING", topic, "", null, 0, 0, List.of(), "", null);
    }
    public static State begin(String topic, String source, Lesson lesson) {
        return begin(topic, source, lesson, null);
    }
    public static State begin(String topic, String source, Lesson lesson, Materials materials) {
        requireText(topic, 200); requireText(source, 4000); validate(lesson);
        return new State(1, "DIAGNOSING", topic, source, lesson, 0, 0, List.of(), "", materials,
                new SelectionState(TutorQuestionSelector.policyFor(lesson), List.of()));
    }
    public static State advance(State state, int version, String action, String answer) {
        if (state.version() != version) throw new BusinessException(409, "学习进度已更新，请刷新后继续。");
        if (state.selection() != null && !TutorQuestionSelector.supports(state.selection().policy()))
            throw new BusinessException(500, "保存的选题规则无法识别，请重新打开会话。");
        if ("continue".equals(action) && List.of("TEACHING", "RETEACHING").contains(state.stage())) {
            if (state.selection() != null) return select(state, "CHECKING", state.evidence(), "");
            return copy(state, "CHECKING", state.checkIndex(), state.practiceIndex(), state.evidence(), "");
        }
        Question q = question(state);
        if (!"answer".equals(action) || q == null) throw new BusinessException(400, "请按当前学习步骤继续，不能跳过检查。");
        String choice = answer == null ? "" : answer.trim().toUpperCase(Locale.ROOT);
        if (choice.length() != 1 || choice.charAt(0) < 'A' || choice.charAt(0) >= 'A' + q.options().size()) {
            throw new BusinessException(400, "请选择一个选项；“懂了”或模糊回答不能代替作答。");
        }
        boolean correct = choice.equals(q.correctOption());
        var evidence = new ArrayList<>(state.evidence());
        evidence.add(new Evidence(state.stage(), q.prompt(), choice, correct));
        String feedback = (correct ? "本题与参考答案一致。" : "本题与参考答案不一致。") + q.explanation();
        if (!correct) {
            feedback = "你选择了 " + choice + ". " + q.options().get(choice.charAt(0) - 'A')
                    + "\n\n本题参考选项：" + q.correctOption() + ". " + q.options().get(q.correctOption().charAt(0) - 'A')
                    + "\n\n" + feedback;
            OptionFeedback selected = optionFeedback(q, choice);
            if (selected != null && selected.focus() != null && !selected.focus().isBlank())
                feedback += "\n\n本次需要区分：" + selected.focus();
        }
        String stage; int check = state.checkIndex(), practice = state.practiceIndex();
        if ("DIAGNOSING".equals(state.stage())) stage = "TEACHING";
        else if (state.selection() != null) {
            if (correct && "CHECKING".equals(state.stage())) return select(state, "PRACTICE", List.copyOf(evidence), feedback);
            if (correct) stage = "FEEDBACK";
            else stage = TutorQuestionSelector.choose(state.selection().policy(), state.lesson(), evidence, "CHECKING") != null
                    && TutorQuestionSelector.choose(state.selection().policy(), state.lesson(), evidence, "PRACTICE") != null ? "RETEACHING" : "REVIEW";
        }
        else if (correct) stage = "CHECKING".equals(state.stage()) ? "PRACTICE" : "FEEDBACK";
        else {
            check++;
            if ("PRACTICE".equals(state.stage())) practice++;
            stage = check < state.lesson().checks().size() && practice < state.lesson().practices().size()
                    ? "RETEACHING" : "REVIEW";
        }
        return copy(state, stage, check, practice, List.copyOf(evidence), feedback);
    }
    private static State copy(State s, String stage, int check, int practice, List<Evidence> evidence, String feedback) {
        return new State(s.version() + 1, stage, s.topic(), s.source(), s.lesson(), check, practice, evidence, feedback, s.materials(), s.selection());
    }
    private static State select(State s, String stage, List<Evidence> evidence, String feedback) {
        var choice = TutorQuestionSelector.choose(s.selection().policy(), s.lesson(), evidence, stage);
        if (choice == null) return copy(s, "REVIEW", s.checkIndex(), s.practiceIndex(), evidence, feedback);
        var history = new ArrayList<>(s.selection().history());
        history.add(choice.selection());
        return new State(s.version() + 1, stage, s.topic(), s.source(), s.lesson(),
                "CHECKING".equals(stage) ? choice.index() : s.checkIndex(),
                "PRACTICE".equals(stage) ? choice.index() : s.practiceIndex(), evidence, feedback, s.materials(),
                new SelectionState(s.selection().policy(), List.copyOf(history)));
    }
    public static void validate(Lesson lesson) {
        if (lesson == null) throw new BusinessException(600, "教学内容不完整，请重试。");
        requireText(lesson.explanation(), 5000); requireText(lesson.remedialExplanation(), 5000);
        if (lesson.checks() == null || lesson.checks().size() != 2 || lesson.practices() == null || lesson.practices().size() != 2)
            throw new BusinessException(600, "教学内容缺少不同的检查题和练习题，请重试。");
        var questions = new ArrayList<Question>(); questions.add(lesson.diagnosis());
        questions.addAll(lesson.checks()); questions.addAll(lesson.practices());
        var prompts = new HashSet<String>();
        for (Question q : questions) {
            if (q == null) throw new BusinessException(600, "教学题目不完整，请重试。");
            requireText(q.prompt(), 1500); requireText(q.explanation(), 2000);
            if (!prompts.add(q.prompt().replaceAll("\\s+", ""))) throw new BusinessException(600, "检查题重复，请重新生成。");
            if (q.options() == null || q.options().size() < 2 || q.options().size() > 4
                    || new HashSet<>(q.options()).size() != q.options().size()) throw new BusinessException(600, "题目选项无效，请重试。");
            q.options().forEach(option -> requireText(option, 1000));
            if (q.correctOption() == null || q.correctOption().length() != 1 || q.correctOption().charAt(0) < 'A'
                    || q.correctOption().charAt(0) >= 'A' + q.options().size()) throw new BusinessException(600, "题目参考答案无效，请重试。");
        }
    }
    private static void requireText(String text, int max) {
        if (text == null || text.isBlank() || text.length() > max) throw new BusinessException(600, "教学内容为空或过长，请检查资料后重试。");
    }
    public static void validatePrepared(Lesson lesson, String source) {
        validate(lesson);
        requireText(source, 4000);
        for (Question q : allQuestions(lesson)) {
            if (q.wrongOptionFeedback() == null || q.wrongOptionFeedback().size() != q.options().size() - 1)
                throw new BusinessException(600, "题目缺少针对错误选项的补充说明，请重试。");
            var options = new HashSet<String>();
            for (OptionFeedback feedback : q.wrongOptionFeedback()) {
                if (feedback == null || feedback.option() == null || feedback.option().length() != 1
                        || feedback.option().charAt(0) < 'A' || feedback.option().charAt(0) >= 'A' + q.options().size()
                        || feedback.option().equals(q.correctOption()) || !options.add(feedback.option()))
                    throw new BusinessException(600, "补充说明与题目选项不匹配，请重试。");
                requireText(feedback.explanation(), 1000);
                if (feedback.evidence() != null) {
                    requireText(feedback.focus(), 120);
                    if (feedback.evidence().isEmpty() || feedback.evidence().size()>4
                            || feedback.sourceQuote()!=null
                            || new HashSet<>(feedback.evidence()).size()!=feedback.evidence().size()
                            || !TutorLessonPreparation.evidence(source).containsAll(feedback.evidence()))
                        throw new BusinessException(600,"补讲中的证据与本轮资料不匹配，请重试。");
                } else {
                    requireText(feedback.focus(), 200);
                    requireText(feedback.sourceQuote(), 300);
                    String quote = feedback.sourceQuote().trim();
                    if (quote.length() < 8 || !source.contains(quote))
                        throw new BusinessException(600, "补讲中的引用无法在本轮资料中找到，请重试。");
                }
            }
        }
        if (lesson.diagnosis().wrongOptionFeedback().stream().allMatch(feedback -> feedback.evidence() != null)) {
            var diagnosisFocuses = focuses(List.of(lesson.diagnosis()));
            var checkFocuses = focuses(lesson.checks());
            var practiceFocuses = focuses(lesson.practices());
            if (!checkFocuses.containsAll(diagnosisFocuses) || !practiceFocuses.containsAll(diagnosisFocuses)
                    || !practiceFocuses.containsAll(checkFocuses))
                throw new BusinessException(600,"备课区分点未覆盖后续检查和练习，请重试。");
        }
    }
    private static HashSet<String> focuses(List<Question> questions) {
        var focuses = new HashSet<String>();
        questions.forEach(question -> question.wrongOptionFeedback().forEach(feedback -> focuses.add(feedback.focus().trim())));
        return focuses;
    }
    private static List<Question> allQuestions(Lesson lesson) {
        var questions = new ArrayList<Question>();
        questions.add(lesson.diagnosis()); questions.addAll(lesson.checks()); questions.addAll(lesson.practices());
        return questions;
    }
    private static OptionFeedback optionFeedback(Question question, String option) {
        if (question.wrongOptionFeedback() == null) return null;
        return question.wrongOptionFeedback().stream().filter(item -> option.equals(item.option())).findFirst().orElse(null);
    }
    private static String selectedSupport(State state) {
        if (state.lesson() == null || state.evidence().isEmpty()) return "";
        Evidence last = state.evidence().get(state.evidence().size() - 1);
        if (last.correct()) return "";
        for (Question question : allQuestions(state.lesson())) {
            if (!question.prompt().equals(last.question())) continue;
            OptionFeedback selected = optionFeedback(question, last.selectedOption());
            if (selected != null) {
                List<String> quotes = selected.evidence() == null ? List.of(selected.sourceQuote())
                        : selected.evidence().stream().map(SourceEvidence::text).toList();
                return "针对这个选项：\n\n" + selected.explanation() + "\n\n资料原文：\n\n"
                        + quotes.stream().map(text -> "> " + text.trim().replace("\n", "\n> "))
                        .collect(java.util.stream.Collectors.joining("\n\n"));
            }
        }
        return "";
    }
    private static Question question(State s) {
        if (s.lesson() == null) return null;
        return switch (s.stage()) {
            case "DIAGNOSING" -> s.lesson().diagnosis();
            case "CHECKING" -> s.lesson().checks().get(s.checkIndex());
            case "PRACTICE" -> s.lesson().practices().get(s.practiceIndex());
            default -> null;
        };
    }
    public static View view(State s) {
        Question q = question(s);
        String support = selectedSupport(s);
        String prompt = q == null ? switch (s.stage()) {
            case "CLARIFYING" -> "请说明“" + s.topic() + "”所属领域，并补充一段定义或参考资料。确认含义后再开始诊断。";
            case "TEACHING" -> (support.isEmpty() ? "" : support + "\n\n") + s.lesson().explanation();
            case "RETEACHING" -> support.isEmpty() ? s.lesson().remedialExplanation() : support;
            case "FEEDBACK" -> "本轮检查和练习已通过。这只是本次作答记录，不代表已经完全掌握。";
            case "REVIEW" -> (support.isEmpty() ? "" : support + "\n\n")
                    + "本轮不同检查题已用完，尚未通过。请回顾资料，结束后可重新开始；不会标记已掌握。";
            default -> throw new BusinessException(500, "学习进度无法识别，请重新打开会话。");
        } : q.prompt();
        return new View(s.version(), s.stage(), s.topic(), prompt, q == null ? List.of() : q.options(),
                List.of("TEACHING", "RETEACHING").contains(s.stage()), "FEEDBACK".equals(s.stage()), s.feedback(), s.materials() != null,
                selectionReason(s, q));
    }
    private static String selectionReason(State s, Question q) {
        if (q == null || s.selection() == null || s.selection().history().isEmpty()) return null;
        var last = s.selection().history().get(s.selection().history().size() - 1);
        return s.stage().equals(last.stage()) && q.prompt().equals(last.question()) ? TutorQuestionSelector.reason(last) : null;
    }
    public static String render(State state) {
        View v = view(state);
        StringBuilder text = new StringBuilder(v.feedback().isBlank() ? "" : v.feedback() + "\n\n");
        if (v.selectionReason() != null) text.append(v.selectionReason()).append("\n\n");
        text.append(v.prompt());
        for (int i=0;i<v.options().size();i++) text.append("\n\n").append((char)('A'+i)).append(". ").append(v.options().get(i));
        if (!v.options().isEmpty()) text.append("\n\n请选择一个选项（只填写字母），不确定可以先回顾资料。");
        return text.toString();
    }
    public static State read(String json) {
        if (json == null || json.isBlank()) return null;
        try { return JSON.readValue(json, State.class); }
        catch (Exception ex) { throw new BusinessException(500, "保存的学习进度无法读取。"); }
    }
    public static String write(State state) {
        try { return JSON.writeValueAsString(state); }
        catch (Exception ex) { throw new BusinessException(500, "学习进度保存失败。"); }
    }
}
