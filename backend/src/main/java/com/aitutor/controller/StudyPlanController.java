package com.aitutor.controller;

import com.aitutor.common.Result;
import com.aitutor.dto.GenerateStudyPlanRequest;
import com.aitutor.service.LearningAnalysisService;
import com.aitutor.vo.StudyPlanVO;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/study-plans")
public class StudyPlanController {

    private final LearningAnalysisService learningAnalysisService;

    public StudyPlanController(LearningAnalysisService learningAnalysisService) {
        this.learningAnalysisService = learningAnalysisService;
    }

    @PostMapping("/generate")
    public Result<StudyPlanVO> generate(@Valid @RequestBody GenerateStudyPlanRequest request) {
        return Result.success(learningAnalysisService.generateStudyPlan(request));
    }
}
