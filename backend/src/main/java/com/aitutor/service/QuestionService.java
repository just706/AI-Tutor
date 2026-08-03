package com.aitutor.service;

import com.aitutor.dto.GenerateQuestionsRequest;
import com.aitutor.dto.SubmitAnswerRequest;
import com.aitutor.vo.AnswerResultVO;
import com.aitutor.vo.QuestionVO;

import java.util.List;

public interface QuestionService {

    List<QuestionVO> generate(GenerateQuestionsRequest request);

    List<QuestionVO> list(Long knowledgePointId, String questionType, String difficulty);

    QuestionVO get(Long id);

    AnswerResultVO answer(Long questionId, SubmitAnswerRequest request);
}
