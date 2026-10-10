package com.aitutor.ai;

import java.util.List;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;
import com.aitutor.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 循序学习的状态与题目；正确答案只保存在服务端。 */
public final class TutorLearningFlow {
    public record Question(String prompt, List<String> options, String correctOption, String explanation) {}
    public record Lesson(String explanation, String remedialExplanation, Question diagnosis,
                         List<Question> checks, List<Question> practices) {}
    public record Evidence(String stage, String question, String selectedOption, boolean correct) {}
    public record State(int version, String stage, String topic, String source, Lesson lesson,
                        int checkIndex, int practiceIndex, List<Evidence> evidence, String feedback) {}
    public record View(int version, String stage, String topic, String prompt, List<String> options,
                       boolean canContinue, boolean passed, String feedback) {}

    private static final ObjectMapper JSON = new ObjectMapper();

    public static State clarify(String topic) {
        requireText(topic, 200);
        return new State(1, "CLARIFYING", topic, "", null, 0, 0, List.of(), "");
    }
    public static State begin(String topic, String source, Lesson lesson) {
        requireText(topic, 200); requireText(source, 4000); validate(lesson);
        return new State(1, "DIAGNOSING", topic, source, lesson, 0, 0, List.of(), "");
    }
    public static State advance(State state, int version, String action, String answer) {
        if (state.version() != version) throw new BusinessException(409, "学习进度已更新，请刷新后继续。");
        if ("continue".equals(action) && List.of("TEACHING", "RETEACHING").contains(state.stage())) {
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
        String stage; int check = state.checkIndex(), practice = state.practiceIndex();
        if ("DIAGNOSING".equals(state.stage())) stage = "TEACHING";
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
        return new State(s.version() + 1, stage, s.topic(), s.source(), s.lesson(), check, practice, evidence, feedback);
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
        String prompt = q == null ? switch (s.stage()) {
            case "CLARIFYING" -> "请说明“" + s.topic() + "”所属领域，并补充一段定义或参考资料。确认含义后再开始诊断。";
            case "TEACHING" -> s.lesson().explanation();
            case "RETEACHING" -> s.lesson().remedialExplanation();
            case "FEEDBACK" -> "本轮检查和练习已通过。这只是本次作答记录，不代表已经完全掌握。";
            case "REVIEW" -> "本轮不同检查题已用完，尚未通过。请回顾资料，结束后可重新开始；不会标记已掌握。";
            default -> throw new BusinessException(500, "学习进度无法识别，请重新打开会话。");
        } : q.prompt();
        return new View(s.version(), s.stage(), s.topic(), prompt, q == null ? List.of() : q.options(),
                List.of("TEACHING", "RETEACHING").contains(s.stage()), "FEEDBACK".equals(s.stage()), s.feedback());
    }
    public static String render(State state) {
        View v = view(state);
        StringBuilder text = new StringBuilder(v.feedback().isBlank() ? "" : v.feedback() + "\n\n");
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
