package com.aitutor.controller;

import com.aitutor.common.Result;
import com.aitutor.dto.GenerateQuestionsRequest;
import com.aitutor.dto.SubmitAnswerRequest;
import com.aitutor.service.QuestionService;
import com.aitutor.vo.AnswerResultVO;
import com.aitutor.vo.QuestionVO;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/questions")
public class QuestionController {

    private final QuestionService questionService;

    public QuestionController(QuestionService questionService) {
        this.questionService = questionService;
    }

    @PostMapping("/generate")
    public Result<List<QuestionVO>> generate(@Valid @RequestBody GenerateQuestionsRequest request) {
        return Result.success(questionService.generate(request));
    }

    @GetMapping
    public Result<List<QuestionVO>> list(@RequestParam(required = false) Long knowledgePointId,
                                         @RequestParam(required = false) String questionType,
                                         @RequestParam(required = false) String difficulty) {
        return Result.success(questionService.list(knowledgePointId, questionType, difficulty));
    }

    @GetMapping("/{questionId}")
    public Result<QuestionVO> get(@PathVariable Long questionId) {
        return Result.success(questionService.get(questionId));
    }

    @PostMapping("/{questionId}/answer")
    public Result<AnswerResultVO> answer(@PathVariable Long questionId,
                                         @Valid @RequestBody SubmitAnswerRequest request) {
        return Result.success(questionService.answer(questionId, request));
    }
}
