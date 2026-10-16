package com.aitutor.ai;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TutorOptionOrderTest {
    private static final String SOURCE = "本题约定为真，其余说法不符合约定。";

    private TutorLearningFlow.Lesson lesson(int... sizes) {
        var questions=new ArrayList<TutorLearningFlow.Question>();
        for(int i=0;i<5;i++) {
            var options=new ArrayList<>(List.of("约定为真","不符合条件一","不符合条件二","不符合条件三").subList(0,sizes[i]));
            Collections.rotate(options,i%sizes[i]);
            var feedback=new ArrayList<TutorLearningFlow.OptionFeedback>();
            for(int j=0;j<options.size();j++) if(!options.get(j).equals("约定为真"))
                feedback.add(new TutorLearningFlow.OptionFeedback(label(j),"约定真假判断","说明："+options.get(j),null,TutorLessonPreparation.evidence(SOURCE)));
            questions.add(new TutorLearningFlow.Question("判断"+i,List.copyOf(options),label(i%sizes[i]),"约定为真符合定义。",List.copyOf(feedback)));
        }
        return new TutorLearningFlow.Lesson("先理解约定。","回顾定义。",questions.get(0),List.copyOf(questions.subList(1,3)),List.copyOf(questions.subList(3,5)));
    }
    private List<TutorLearningFlow.Question> questions(TutorLearningFlow.Lesson lesson) {
        return List.of(lesson.diagnosis(),lesson.checks().get(0),lesson.checks().get(1),lesson.practices().get(0),lesson.practices().get(1));
    }
    private String label(int index) { return String.valueOf((char)('A'+index)); }

    @Test void balancedSchedulesVaryWithoutLosingCorrectTextOrAnyFeedbackAcrossOptionCounts() {
        for(int[] sizes:List.of(new int[]{2,2,2,2,2},new int[]{3,3,3,3,3},new int[]{4,4,4,4,4},new int[]{2,3,4,3,2})) {
            var original=lesson(sizes);
            var schedules=new HashSet<List<String>>();
            for(int seed=0;seed<128;seed++) {
                var reordered=TutorOptionOrder.arrange(original,new Random(seed));
                TutorLearningFlow.validatePrepared(reordered,SOURCE);
                var all=questions(reordered);
                schedules.add(all.stream().map(TutorLearningFlow.Question::correctOption).toList());
                for(int i=0;i<5;i++) {
                    var q=all.get(i); var before=questions(original).get(i);
                    assertEquals(before.prompt(),q.prompt()); assertEquals(before.explanation(),q.explanation());
                    assertEquals(new HashSet<>(before.options()),new HashSet<>(q.options()));
                    assertEquals("约定为真",q.options().get(q.correctOption().charAt(0)-'A'));
                    assertEquals(q.options().size()-1,q.wrongOptionFeedback().size());
                    for(var f:q.wrongOptionFeedback()) {
                        assertEquals("说明："+q.options().get(f.option().charAt(0)-'A'),f.explanation());
                        assertEquals("约定真假判断",f.focus());
                        assertEquals(List.of(new TutorLearningFlow.SourceEvidence("E1",SOURCE)),f.evidence());
                    }
                }
                for(int size=2;size<=4;size++) {
                    int min=5,max=0;
                    for(int slot=0;slot<size;slot++) {
                        int count=0;
                        for(var q:all) if(q.options().size()==size && q.correctOption().equals(label(slot))) count++;
                        min=Math.min(min,count); max=Math.max(max,count);
                    }
                    assertTrue(max-min<=1,"同组选项位置分布不均，seed="+seed);
                }
            }
            assertTrue(schedules.size()>1,"不能每轮使用固定答案顺序");
        }
    }

    @Test void gradingSelectedSupportAndPersistedOrderSurviveEveryFlowStage() {
        var lesson=TutorOptionOrder.arrange(lesson(3,3,3,3,3),new Random(21));
        var initial=TutorLearningFlow.begin("约定",SOURCE,lesson);
        String saved=TutorLearningFlow.write(initial);
        assertEquals(saved,TutorLearningFlow.write(TutorLearningFlow.read(saved)));
        for(var f:lesson.diagnosis().wrongOptionFeedback()) {
            var wrong=TutorLearningFlow.advance(TutorLearningFlow.read(saved),1,"answer",f.option());
            assertFalse(wrong.evidence().get(0).correct());
            assertTrue(TutorLearningFlow.render(wrong).contains(f.explanation()));
            assertTrue(TutorLearningFlow.render(wrong).contains(SOURCE));
        }
        var state=TutorLearningFlow.advance(initial,1,"answer",lesson.diagnosis().correctOption());
        assertTrue(state.evidence().get(0).correct());
        state=TutorLearningFlow.advance(state,state.version(),"continue","");
        state=TutorLearningFlow.advance(state,state.version(),"answer",lesson.checks().get(0).correctOption());
        assertEquals("PRACTICE",state.stage());
        var wrong=lesson.practices().get(0).wrongOptionFeedback().get(0);
        state=TutorLearningFlow.advance(state,state.version(),"answer",wrong.option());
        state=TutorLearningFlow.read(TutorLearningFlow.write(state));
        assertEquals("RETEACHING",state.stage());
        assertTrue(TutorLearningFlow.render(state).contains(wrong.explanation()));
        state=TutorLearningFlow.advance(state,state.version(),"continue","");
        state=TutorLearningFlow.advance(state,state.version(),"answer",lesson.checks().get(1).correctOption());
        state=TutorLearningFlow.advance(state,state.version(),"answer",lesson.practices().get(1).correctOption());
        assertEquals("FEEDBACK",state.stage());
        assertEquals(lesson,state.lesson());
    }

    @Test void knownLetterAndPositionReferencesKeepQuestionUnchanged() {
        var base=lesson(3,3,3,3,3); var q=base.diagnosis();
        for(String text:List.of("选项A正确","B 是条件名称","选项 C","A和B均不对","以上皆是","以上选项均不符合","第一个选项","前两项均对","其他选项都错误","All of the above","the second option","choice 2","option a","选项b","b选项错误")) {
            for(int field=0;field<4;field++) {
                var options=field==1?List.of(text,q.options().get(1),q.options().get(2)):q.options();
                var feedback=field==3?List.of(new TutorLearningFlow.OptionFeedback("B","位置引用",text,null,TutorLessonPreparation.evidence(SOURCE)),q.wrongOptionFeedback().get(1)):q.wrongOptionFeedback();
                var dependent=new TutorLearningFlow.Question(field==0?text:q.prompt(),options,q.correctOption(),field==2?text:q.explanation(),feedback);
                var input=new TutorLearningFlow.Lesson(base.explanation(),base.remedialExplanation(),dependent,base.checks(),base.practices());
                assertEquals(dependent,TutorOptionOrder.arrange(input,new Random(4)).diagnosis(),"必须保留依赖位置的题目："+text);
            }
        }
        var shared=new TutorLearningFlow.Lesson("第一题请关注选项 A。",base.remedialExplanation(),q,base.checks(),base.practices());
        assertEquals(shared,TutorOptionOrder.arrange(shared,new Random(4)));
    }

    @Test void readingLegacyLessonsDoesNotReorderOrReinterpretRecordedAnswers() {
        var original=lesson(2,2,2,2,2);
        var old=questions(original).stream().map(q->new TutorLearningFlow.Question(q.prompt(),q.options(),q.correctOption(),q.explanation())).toList();
        var legacy=new TutorLearningFlow.Lesson(original.explanation(),original.remedialExplanation(),old.get(0),old.subList(1,3),old.subList(3,5));
        var state=TutorLearningFlow.begin("约定",SOURCE,legacy);
        state=TutorLearningFlow.advance(state,1,"answer","A");
        String saved=TutorLearningFlow.write(state);
        assertEquals(saved,TutorLearningFlow.write(TutorLearningFlow.read(saved)));
        assertEquals(legacy,TutorLearningFlow.read(saved).lesson());
        assertTrue(TutorLearningFlow.read(saved).evidence().get(0).correct());
    }
}
