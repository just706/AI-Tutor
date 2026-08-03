package com.aitutor.controller;

import com.aitutor.common.Result;
import com.aitutor.service.LearningAnalysisService;
import com.aitutor.vo.LearningAnalysisOverviewVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/analysis")
public class AnalysisController {

    private final LearningAnalysisService learningAnalysisService;

    public AnalysisController(LearningAnalysisService learningAnalysisService) {
        this.learningAnalysisService = learningAnalysisService;
    }

    @GetMapping("/overview")
    public Result<LearningAnalysisOverviewVO> overview() {
        return Result.success(learningAnalysisService.overview());
    }
}
