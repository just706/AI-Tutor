package com.aitutor.ai;

import com.aitutor.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TutorOptionFeedbackTest {
    private static final String SOURCE="数学：导数表示瞬时变化率；积分表示累积量。导数可以用切线斜率理解。";
    private static final TutorLearningFlow.OptionFeedback WRONG_B=new TutorLearningFlow.OptionFeedback(
            "B","变化率与累积量","累积量对应积分；本题问变化率，需回到导数的定义。","导数表示瞬时变化率；积分表示累积量。");
    private static final TutorLearningFlow.OptionFeedback WRONG_C=new TutorLearningFlow.OptionFeedback(
            "C","切线斜率与面积","本题要用切线斜率理解导数，不能把它替换成面积。","导数可以用切线斜率理解。");
    private static TutorLearningFlow.Question question(String prompt,List<TutorLearningFlow.OptionFeedback> feedback) {
        return new TutorLearningFlow.Question(prompt,List.of("瞬时变化率","累积量","面积"),"A","依据导数的定义判断。",feedback);
    }
    private static TutorLearningFlow.Lesson lesson(List<TutorLearningFlow.OptionFeedback> feedback) {
        return new TutorLearningFlow.Lesson("核心讲解：先理解导数。","旧版通用补讲。",question("诊断",feedback),
                List.of(question("检查一",feedback),question("检查二",feedback)),
                List.of(question("应用一",feedback),question("应用二",feedback)));
    }
    private static TutorLearningFlow.State begin() {
        return TutorLearningFlow.begin("导数",SOURCE,lesson(List.of(WRONG_B,WRONG_C)));
    }
    private static TutorLearningFlow.State step(TutorLearningFlow.State s,String action,String answer) {
        return TutorLearningFlow.advance(s,s.version(),action,answer);
    }
    private static TutorLearningFlow.State checking() {
        return step(step(begin(),"answer","A"),"continue","");
    }
    @Test void differentWrongChoicesGetTheirOwnExplanationAndQuote() {
        String b=TutorLearningFlow.render(step(checking(),"answer","B"));
        String c=TutorLearningFlow.render(step(checking(),"answer","C"));
        assertTrue(b.contains(WRONG_B.explanation())); assertTrue(b.contains(WRONG_B.sourceQuote()));
        assertTrue(b.contains("B. 累积量")); assertTrue(b.contains(WRONG_B.focus()));
        assertFalse(b.contains(WRONG_C.explanation())); assertFalse(b.contains("旧版通用补讲"));
        assertTrue(c.contains(WRONG_C.explanation())); assertFalse(c.contains(WRONG_B.explanation()));
    }
    @Test void wrongDiagnosisGetsSpecificSupportBeforeMainTeachingAndCorrectChoiceDoesNot() {
        String wrong=TutorLearningFlow.render(step(begin(),"answer","B"));
        assertTrue(wrong.contains(WRONG_B.explanation())); assertTrue(wrong.contains("核心讲解"));
        String correct=TutorLearningFlow.render(step(begin(),"answer","A"));
        assertFalse(correct.contains(WRONG_B.explanation())); assertFalse(correct.contains(WRONG_C.explanation()));
    }
    @Test void practiceMistakeKeepsFeedbackAfterReloadAndExhaustionStillExplainsIt() {
        var s=step(step(checking(),"answer","A"),"answer","C");
        assertEquals("RETEACHING",s.stage());
        s=TutorLearningFlow.read(TutorLearningFlow.write(s));
        assertTrue(TutorLearningFlow.render(s).contains(WRONG_C.explanation()));
        s=step(s,"continue",""); assertEquals("检查二",TutorLearningFlow.view(s).prompt());
        s=step(s,"answer","B"); assertEquals("REVIEW",s.stage());
        assertTrue(TutorLearningFlow.render(s).contains(WRONG_B.explanation()));
        assertFalse(TutorLearningFlow.view(s).passed());
    }
    @Test void unansweredAndFutureFeedbackStayPrivate() throws Exception {
        String visible=new ObjectMapper().writeValueAsString(TutorLearningFlow.view(checking()));
        assertFalse(visible.contains(WRONG_B.explanation())); assertFalse(visible.contains(WRONG_C.explanation()));
        assertFalse(visible.contains("wrongOptionFeedback")); assertFalse(visible.contains("correctOption"));
        assertEquals(3,TutorLearningFlow.view(checking()).options().size());
    }
    @Test void newPreparationRequiresEveryWrongOptionExactlyOnceAndNoCorrectOption() {
        for(var feedback:List.of(List.<TutorLearningFlow.OptionFeedback>of(),List.of(WRONG_B),
                List.of(WRONG_B,WRONG_B),List.of(WRONG_B,new TutorLearningFlow.OptionFeedback("A","主题","解释",WRONG_C.sourceQuote())),
                List.of(WRONG_B,new TutorLearningFlow.OptionFeedback("D","主题","解释",WRONG_C.sourceQuote())))) {
            assertThrows(BusinessException.class,()->TutorLearningFlow.validatePrepared(lesson(feedback),SOURCE));
        }
        assertThrows(BusinessException.class,()->TutorLearningFlow.validatePrepared(lesson(null),SOURCE));
        assertDoesNotThrow(()->TutorLearningFlow.validatePrepared(lesson(List.of(WRONG_B,WRONG_C)),SOURCE));
    }
    @Test void unsupportedQuotesAndEmptyExplanationsRejectEntirePreparation() {
        for(var item:List.of(new TutorLearningFlow.OptionFeedback("C","主题","解释","教材没有说过的依据。"),
                new TutorLearningFlow.OptionFeedback("C","主题","",WRONG_C.sourceQuote()),
                new TutorLearningFlow.OptionFeedback("C","","解释",WRONG_C.sourceQuote()),
                new TutorLearningFlow.OptionFeedback("C","主题","解释","导数"))) {
            assertThrows(BusinessException.class,()->TutorLearningFlow.validatePrepared(lesson(List.of(WRONG_B,item)),SOURCE));
        }
    }
    @Test void legacyLessonsWithoutOptionFeedbackRemainUsableWithoutInventingSpecificErrors() {
        var legacy=TutorLearningFlow.begin("导数",SOURCE,lesson(null));
        legacy=TutorLearningFlow.read(TutorLearningFlow.write(legacy));
        var s=step(step(step(legacy,"answer","A"),"continue",""),"answer","B");
        assertEquals("RETEACHING",s.stage());
        assertTrue(TutorLearningFlow.render(s).contains("旧版通用补讲"));
        assertFalse(TutorLearningFlow.render(s).contains(WRONG_B.focus()));
    }
}
