package com.aitutor.service.impl;

import com.aitutor.entity.AnswerRecord;
import com.aitutor.entity.KnowledgePoint;
import com.aitutor.entity.Question;
import com.aitutor.mapper.*;
import com.aitutor.security.CurrentUser;
import com.aitutor.security.UserContext;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class LearningAnalysisEvidenceTest {
    @AfterEach void cleanup() { UserContext.clear(); }

    @Test void unscoredAnswersDoNotInventWeaknessOrWrongAnswers() {
        var service = service(List.of(answer(null, null)));
        var overview = service.overview();
        assertTrue(overview.getWeakKnowledgePoints().isEmpty());
        assertEquals(1, overview.getAnsweredQuestionCount());
        var recent = service.recentAnswers(1).get(0);
        assertNull(recent.getScore()); assertNull(recent.getCorrect());
    }

    @Test void unscoredAnswersDoNotDiluteGradedAccuracyOrAverage() {
        var service = service(List.of(answer(1, 100), answer(null, null)));
        assertEquals(100, service.overview().getAnswerAccuracy());
        var progress = service.knowledgePointProgress().get(0);
        assertEquals(2, progress.getAnsweredQuestionCount());
        assertEquals(100, progress.getAnswerAccuracy());
        assertEquals(100, progress.getAverageScore());
        assertTrue(service.overview().getWeakKnowledgePoints().isEmpty());
    }

    private AnswerRecord answer(Integer correct, Integer score) {
        var answer = new AnswerRecord(); answer.setQuestionId(11L); answer.setIsCorrect(correct); answer.setScore(score);
        return answer;
    }

    private LearningAnalysisServiceImpl service(List<AnswerRecord> input) {
        UserContext.set(new CurrentUser(7L, "tester", "student"));
        var records = mock(LearningRecordMapper.class);
        var answers = mock(AnswerRecordMapper.class);
        var questions = mock(QuestionMapper.class);
        var points = mock(KnowledgePointMapper.class);
        when(answers.selectList(any(LambdaQueryWrapper.class))).thenReturn(input);
        var question = new Question(); question.setId(11L); question.setKnowledgePointId(2L);
        when(questions.selectByIds(any())).thenReturn(List.of(question));
        var point = new KnowledgePoint(); point.setId(2L); point.setName("Parity");
        when(points.selectByIds(any())).thenReturn(List.of(point));
        return new LearningAnalysisServiceImpl(records, answers, questions, points,
                mock(ChatHistoryMapper.class), mock(StudentProfileMapper.class));
    }
}
