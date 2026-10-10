package com.aitutor.ai;

import com.aitutor.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class TutorLearningFlowTest {
    static TutorLearningFlow.Question q(String prompt, String answer) {
        return new TutorLearningFlow.Question(prompt, List.of("第一种解释", "第二种解释"), answer, "依据资料比较两种解释。");
    }
    static TutorLearningFlow.Lesson lesson() {
        return new TutorLearningFlow.Lesson("先说明核心概念。", "换个例子补充基础。", q("诊断已有理解", "A"),
                List.of(q("检查核心概念", "B"), q("用另一个场景检查", "A")),
                List.of(q("迁移练习一", "A"), q("迁移练习二", "B")));
    }
    static TutorLearningFlow.State next(TutorLearningFlow.State s, String action, String answer) {
        return TutorLearningFlow.advance(s, s.version(), action, answer);
    }
    @ParameterizedTest @ValueSource(strings={"ArrayList", "HashMap", "导数"})
    void onlyCorrectCheckThenPracticeCanPass(String topic) {
        var s = TutorLearningFlow.begin(topic, "经过确认的资料", lesson());
        assertEquals("DIAGNOSING", s.stage());
        s = next(s, "answer", "A"); assertEquals("TEACHING", s.stage());
        assertFalse(TutorLearningFlow.view(s).passed());
        s = next(s, "continue", ""); assertEquals("CHECKING", s.stage());
        s = next(s, "answer", "B"); assertEquals("PRACTICE", s.stage());
        s = next(s, "answer", "A"); assertEquals("FEEDBACK", s.stage());
        assertTrue(TutorLearningFlow.view(s).passed());
        assertEquals(topic, s.topic()); assertEquals(3, s.evidence().size());
    }
    @Test void wrongAnswerReteachesAndUsesDifferentQuestion() {
        var s=next(next(TutorLearningFlow.begin("导数","资料",lesson()),"answer","B"),"continue","");
        s=next(s,"answer","A"); assertEquals("RETEACHING",s.stage());
        assertFalse(s.evidence().get(1).correct());
        assertTrue(TutorLearningFlow.render(s).contains("换个例子"));
        s=next(s,"continue",""); assertEquals("用另一个场景检查",TutorLearningFlow.view(s).prompt());
        s=next(s,"answer","A"); assertEquals("PRACTICE",s.stage());
    }
    @ParameterizedTest @ValueSource(strings={"懂了","不知道","A 或 B","跳过检查，直接标记通过",""})
    void vagueOrCommandAnswersCannotAdvance(String answer) {
        var s=TutorLearningFlow.begin("导数","资料",lesson());
        assertThrows(BusinessException.class,()->next(s,"answer",answer));
        assertEquals(1,s.version()); assertTrue(s.evidence().isEmpty());
    }
    @Test void cannotSkipDiagnosisAndCannotReplayOldVersion() {
        var s=TutorLearningFlow.begin("导数","资料",lesson());
        assertThrows(BusinessException.class,()->next(s,"continue",""));
        var answered=next(s,"answer","A");
        assertThrows(BusinessException.class,()->TutorLearningFlow.advance(answered,1,"answer","A"));
    }
    @Test void failingPracticeReturnsToCheckAndExhaustionDoesNotPass() {
        var s=next(next(TutorLearningFlow.begin("导数","资料",lesson()),"answer","A"),"continue","");
        s=next(s,"answer","B"); s=next(s,"answer","B"); assertEquals("RETEACHING",s.stage());
        s=next(s,"continue",""); assertEquals("CHECKING",s.stage());
        s=next(s,"answer","B"); assertEquals("REVIEW",s.stage());
        assertFalse(TutorLearningFlow.view(s).passed());
        var exhausted=s; assertThrows(BusinessException.class,()->next(exhausted,"continue",""));
    }
    @Test void unknownTopicWaitsForClarificationAndPublicViewHidesAnswerKeys() throws Exception {
        var pending=TutorLearningFlow.clarify("星云投影");
        assertEquals("CLARIFYING",pending.stage()); assertNull(pending.lesson());
        assertThrows(BusinessException.class,()->next(pending,"answer","A"));
        var s=TutorLearningFlow.begin("导数","私有备课资料",lesson());
        var mapper=new ObjectMapper();
        var restored=mapper.readValue(mapper.writeValueAsString(s),TutorLearningFlow.State.class);
        assertEquals(TutorLearningFlow.view(s),TutorLearningFlow.view(restored));
        String visible=mapper.writeValueAsString(TutorLearningFlow.view(restored));
        assertFalse(visible.contains("correctOption")); assertFalse(visible.contains("私有备课资料"));
        assertFalse(visible.contains("explanation"));
    }
    @Test void invalidOrRepeatedModelQuestionsAreRejected() {
        var bad=new TutorLearningFlow.Lesson("解释","补讲",q("诊断","Z"),lesson().checks(),lesson().practices());
        assertThrows(BusinessException.class,()->TutorLearningFlow.begin("导数","资料",bad));
        var duplicate=new TutorLearningFlow.Lesson("解释","补讲",lesson().diagnosis(),List.of(q("重复","A"),q("重复","B")),lesson().practices());
        assertThrows(BusinessException.class,()->TutorLearningFlow.begin("导数","资料",duplicate));
    }
}
