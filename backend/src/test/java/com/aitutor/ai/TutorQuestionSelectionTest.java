package com.aitutor.ai;

import org.junit.jupiter.api.Test;
import com.aitutor.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static com.aitutor.ai.TutorLearningFlow.*;
import static org.junit.jupiter.api.Assertions.*;

class TutorQuestionSelectionTest {
    static final SourceEvidence RED = new SourceEvidence("E1", "亮纹代表红色。");
    static final SourceEvidence BLUE = new SourceEvidence("E2", "暗纹代表蓝色。");
    static final SourceEvidence GREEN = new SourceEvidence("E3", "无纹代表绿色。");

    static OptionFeedback feedback(String option, SourceEvidence... evidence) {
        return new OptionFeedback(option, null, "请比较这些原文中的条件。", null, List.of(evidence));
    }
    static OptionFeedback feedback(String option, String focus, SourceEvidence... evidence) {
        return new OptionFeedback(option, focus, "请比较这些原文中的条件。", null, List.of(evidence));
    }
    static Question q(String prompt, SourceEvidence evidence) {
        return new Question(prompt, List.of("资料中的颜色", "无法确定"), "A", "依据给定规则。",
                List.of(feedback("B", evidence)));
    }
    static Lesson lesson() {
        return new Lesson("区分三种纹路。", "回顾对应关系。",
                new Question("亮纹代表什么颜色？", List.of("红色", "蓝色", "绿色"), "A", "亮纹代表红色。",
                        List.of(feedback("B", RED, BLUE), feedback("C", RED, GREEN))),
                List.of(q("暗纹代表什么颜色？", BLUE), q("无纹代表什么颜色？", GREEN)),
                List.of(q("暗纹卡片应涂什么颜色？", BLUE), q("无纹卡片应涂什么颜色？", GREEN)));
    }
    static State begin() { return TutorLearningFlow.begin("纹路颜色", "亮纹代表红色。暗纹代表蓝色。无纹代表绿色。", lesson()); }
    static State next(State s, String action, String answer) { return advance(s, s.version(), action, answer); }
    static State firstCheck(String choice) { return next(next(begin(), "answer", choice), "continue", ""); }

    @Test void differentWrongOptionsChooseChecksSharingTheirRemediationEvidence() {
        assertEquals("暗纹代表什么颜色？", view(firstCheck("B")).prompt());
        assertEquals("无纹代表什么颜色？", view(firstCheck("C")).prompt());
    }

    @Test void observableDistinctionWinsWhenBroadEvidenceIsShared() {
        assertFocusWins("HashMap 中同键再次 put 会怎样？", "键唯一与值唯一", "同键替换与多值并存");
        assertFocusWins("导数描述哪一种变化率？", "区间平均变化率", "一点处的瞬时变化率");
        assertFocusWins("编号四应使用哪种颜色？", "编号与颜色直接对应", "编号加三后颜色不变");
    }

    private static void assertFocusWins(String prompt, String otherFocus, String targetFocus) {
        var diagnosis = new Question(prompt, List.of("正确结论", "错误结论"), "A", "依据资料判断。",
                List.of(feedback("B", targetFocus, RED)));
        var unrelated = new Question("先出现的其他区分题："+otherFocus, List.of("正确", "错误"), "A", "依据资料判断。",
                List.of(feedback("B", otherFocus, RED)));
        var targeted = new Question("后出现的目标区分题："+targetFocus, List.of("正确", "错误"), "A", "依据资料判断。",
                List.of(feedback("B", targetFocus, RED)));
        var l = new Lesson("讲解", "补讲", diagnosis, List.of(unrelated, targeted), lesson().practices());
        var choice = TutorQuestionSelector.choose(TutorQuestionSelector.POLICY, l,
                List.of(new Evidence("DIAGNOSING", diagnosis.prompt(), "B", false)), "CHECKING");
        assertEquals(1, choice.index(), prompt);
        assertEquals("DISTINCTION_MATCH", choice.selection().reason());
    }

    @Test void noDistinctionMatchUsesOriginalOrderWithoutEvidenceTargetingClaim() {
        var diagnosis = new Question("诊断", List.of("正确", "错误"), "A", "说明",
                List.of(feedback("B", "目标区分点", RED)));
        var first = new Question("第一题", List.of("正确", "错误"), "A", "说明",
                List.of(feedback("B", "其他区分点一", BLUE)));
        var second = new Question("第二题", List.of("正确", "错误"), "A", "说明",
                List.of(feedback("B", "其他区分点二", RED)));
        var l = new Lesson("讲解", "补讲", diagnosis, List.of(first, second), lesson().practices());
        var choice = TutorQuestionSelector.choose(TutorQuestionSelector.POLICY, l,
                List.of(new Evidence("DIAGNOSING", "诊断", "B", false)), "CHECKING");
        assertEquals(0, choice.index());
        assertEquals("NO_DISTINCTION_MATCH", choice.selection().reason());
        assertTrue(choice.selection().sharedEvidenceIds().isEmpty());
        String visibleReason=TutorQuestionSelector.reason(choice.selection());
        assertFalse(visibleReason.contains("目标区分点"));
        assertFalse(visibleReason.contains("原文"));
    }

    @Test void incompleteFeedbackCannotEnableNewPolicyOrEvidenceFallback() {
        var empty=new Question("空反馈",List.of("正确","错误"),"A","说明",List.of());
        var emptyLesson=new Lesson("讲解","补讲",empty,List.of(empty,empty),List.of(empty,empty));
        assertEquals(TutorQuestionSelector.LEGACY_POLICY,TutorQuestionSelector.policyFor(emptyLesson));
        var diagnosis=new Question("诊断",List.of("正确","错误"),"A","说明",List.of(feedback("B",RED)));
        var first=new Question("第一题",List.of("正确","错误"),"A","说明",List.of(feedback("B","其他",BLUE)));
        var second=new Question("第二题",List.of("正确","错误"),"A","说明",List.of(feedback("B","其他",RED)));
        var l=new Lesson("讲解","补讲",diagnosis,List.of(first,second),lesson().practices());
        assertEquals(TutorQuestionSelector.LEGACY_POLICY,TutorQuestionSelector.policyFor(l));
        var choice=TutorQuestionSelector.choose(TutorQuestionSelector.POLICY,l,
                List.of(new Evidence("DIAGNOSING","诊断","B",false)),"CHECKING");
        assertEquals(0,choice.index());
        assertEquals("NO_DISTINCTION_MATCH",choice.selection().reason());
        assertTrue(choice.selection().sharedEvidenceIds().isEmpty());
    }

    @Test void curatedCrossDomainMatchingCasesStayStable() throws Exception {
        var root=new ObjectMapper().readTree(Files.readString(Path.of("evaluation/stage2-question-matching-cases.json")));
        assertEquals("stage2-question-matching-v1",root.path("version").asText());
        assertEquals(4,root.path("cases").size());
        for(var item:root.path("cases")) {
            String target=item.path("targetFocus").asText();
            var diagnosis=new Question(item.path("diagnosisPrompt").asText(),List.of("正确","错误"),"A","说明",
                    List.of(feedback("B",target,RED)));
            var candidates=new java.util.ArrayList<Question>();
            for(var focus:item.path("candidateFocuses"))
                candidates.add(new Question("候选："+focus.asText(),List.of("正确","错误"),"A","说明",
                        List.of(feedback("B",focus.asText(),RED))));
            var l=new Lesson("讲解","补讲",diagnosis,List.copyOf(candidates),lesson().practices());
            var choice=TutorQuestionSelector.choose(TutorQuestionSelector.POLICY,l,
                    List.of(new Evidence("DIAGNOSING",diagnosis.prompt(),"B",false)),"CHECKING");
            assertEquals(item.path("expectedIndex").asInt(),choice.index(),item.path("id").asText());
            assertEquals(item.path("expectedReason").asText(),choice.selection().reason(),item.path("id").asText());
        }
    }

    @Test void practiceStillUsesMostRecentMistakeAfterCorrectCheck() {
        var s = next(firstCheck("C"), "answer", "A");
        assertEquals("PRACTICE", s.stage());
        assertEquals("无纹卡片应涂什么颜色？", view(s).prompt());
        var trace = s.selection().history().get(1);
        assertEquals(lesson().diagnosis().prompt(), trace.basedOnQuestion());
        assertEquals("C", trace.selectedOption());
        assertEquals(List.of("E3"), trace.sharedEvidenceIds());
    }

    @Test void wrongPracticeReturnsToUnansweredCheckAndThenUnansweredPractice() {
        var s = next(firstCheck("C"), "answer", "A");
        s = next(s, "answer", "B");
        assertEquals("RETEACHING", s.stage());
        assertNull(view(s).selectionReason());
        s = next(s, "continue", "");
        assertEquals("暗纹代表什么颜色？", view(s).prompt());
        var trace = s.selection().history().get(2);
        assertEquals("无纹卡片应涂什么颜色？", trace.basedOnQuestion());
        assertEquals("NO_SHARED_EVIDENCE", trace.reason());
        s = next(s, "answer", "A");
        assertEquals("暗纹卡片应涂什么颜色？", view(s).prompt());
        s = next(s, "answer", "A");
        assertTrue(view(s).passed());
        assertEquals(5, s.evidence().stream().map(Evidence::question).distinct().count());
        assertEquals(4, s.selection().history().size());
    }

    @Test void wrongChecksCanRunInReverseOrderAndExhaustWithoutPassing() {
        var s = next(firstCheck("C"), "answer", "B");
        assertEquals("RETEACHING", s.stage());
        s = next(s, "continue", "");
        assertEquals(0, s.checkIndex());
        s = next(s, "answer", "B");
        assertEquals("REVIEW", s.stage());
        assertFalse(view(s).passed());
        var exhausted = s;
        assertThrows(BusinessException.class, () -> next(exhausted, "continue", ""));
        assertEquals(3, s.evidence().stream().map(Evidence::question).distinct().count());
    }

    @Test void sourceIdentityRequiresBothIdAndExactText() {
        var different = q("同编号但不同原文", new SourceEvidence("E3", "其他内容。"));
        var wrongId = q("同原文但不同编号", new SourceEvidence("E9", GREEN.text()));
        var l = new Lesson("讲解", "补讲", lesson().diagnosis(), List.of(different, wrongId), lesson().practices());
        var choice = TutorQuestionSelector.choose(l, List.of(new Evidence("DIAGNOSING", l.diagnosis().prompt(), "C", false)), "CHECKING");
        assertEquals(0, choice.index());
        assertEquals("NO_SHARED_EVIDENCE", choice.selection().reason());
        assertTrue(choice.selection().sharedEvidenceIds().isEmpty());
    }

    @Test void greatestDistinctOverlapWinsAndTiesKeepOriginalOrder() {
        var broad = new Question("覆盖两条原文", List.of("对", "错一", "错二"), "A", "说明",
                List.of(feedback("B", RED, GREEN), feedback("C", GREEN)));
        var l = new Lesson("讲解", "补讲", lesson().diagnosis(), List.of(q("单条", GREEN), broad), lesson().practices());
        var answers = List.of(new Evidence("DIAGNOSING", l.diagnosis().prompt(), "C", false));
        assertEquals(1, TutorQuestionSelector.choose(l, answers, "CHECKING").index());
        var tied = new Lesson("讲解", "补讲", l.diagnosis(), List.of(q("先备课的题", GREEN), q("后备课的题", GREEN)), l.practices());
        assertEquals(0, TutorQuestionSelector.choose(tied, answers, "CHECKING").index());
        var duplicate = new Question("重复引用不增加分数", broad.options(), "A", "说明",
                List.of(feedback("B", GREEN), feedback("C", GREEN)));
        var deduped = new Lesson("讲解", "补讲", l.diagnosis(), List.of(q("单条", GREEN), duplicate), l.practices());
        assertEquals(0, TutorQuestionSelector.choose(deduped, answers, "CHECKING").index());
    }

    @Test void latestMistakeReplacesEarlierDiagnosisWhenChoosingPractice() {
        var cross = new Question("无纹代表什么颜色？", List.of("绿色", "蓝色"), "A", "区分无纹与暗纹。",
                List.of(feedback("B", BLUE)));
        var l = new Lesson("讲解", "补讲", lesson().diagnosis(), List.of(lesson().checks().get(0), cross), lesson().practices());
        var answers = List.of(new Evidence("DIAGNOSING", l.diagnosis().prompt(), "C", false),
                new Evidence("CHECKING", cross.prompt(), "B", false),
                new Evidence("CHECKING", l.checks().get(0).prompt(), "A", true));
        var choice = TutorQuestionSelector.choose(l, answers, "PRACTICE");
        assertEquals(0, choice.index());
        assertEquals(cross.prompt(), choice.selection().basedOnQuestion());
        assertEquals(List.of("E2"), choice.selection().sharedEvidenceIds());
    }

    @Test void correctDiagnosisOrMissingFeedbackUsesOriginalOrder() {
        var s = firstCheck("A");
        assertEquals(0, s.checkIndex());
        assertNull(view(s).selectionReason());
        var legacy = TutorLearningFlowTest.lesson();
        var fallback = next(next(TutorLearningFlow.begin("导数", "资料", legacy), "answer", "B"), "continue", "");
        assertEquals(0, fallback.checkIndex());
        assertEquals("NO_SHARED_EVIDENCE", fallback.selection().history().get(0).reason());
    }

    @Test void selectionAndExplanationSurviveReloadWithoutExposingPrivateTrace() throws Exception {
        var s = firstCheck("C");
        var restored = read(write(s));
        assertEquals(s, restored);
        assertEquals(view(s), view(restored));
        assertTrue(render(restored).contains(view(s).selectionReason()));
        String visible = new ObjectMapper().writeValueAsString(view(restored));
        for (String hidden : List.of("sharedEvidenceIds", "basedOnQuestion", "correctOption", "evidence_overlap_v1", "暗纹卡片", "E3"))
            assertFalse(visible.contains(hidden), hidden);
        assertThrows(BusinessException.class, () -> advance(restored, restored.version()-1, "answer", "A"));
        assertEquals(1, restored.selection().history().size());
    }

    @Test void savedLegacyProgressWithoutSelectionRetainsSequentialBehavior() throws Exception {
        ObjectNode json = (ObjectNode) new ObjectMapper().readTree(write(begin()));
        json.remove("selection");
        var old = read(json.toString());
        assertNull(old.selection());
        old = next(next(old, "answer", "C"), "continue", "");
        assertEquals(0, old.checkIndex());
        assertNull(view(old).selectionReason());
        old = next(next(old, "answer", "B"), "continue", "");
        assertEquals(1, old.checkIndex());
        assertNull(read(write(old)).selection());
    }

    @Test void unknownSavedPolicyCannotSilentlyChangeProgress() {
        var s = begin();
        var unknown = new State(s.version(), s.stage(), s.topic(), s.source(), s.lesson(), 0, 0,
                s.evidence(), s.feedback(), null, new SelectionState("unknown", List.of()));
        assertThrows(BusinessException.class, () -> next(unknown, "answer", "A"));
    }
}
