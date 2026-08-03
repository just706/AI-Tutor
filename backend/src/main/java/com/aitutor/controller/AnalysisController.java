package com.aitutor.controller;

import com.aitutor.common.Result;
import com.aitutor.service.LearningAnalysisService;
import com.aitutor.vo.KnowledgePointProgressVO;
import com.aitutor.vo.LearningAnalysisOverviewVO;
import com.aitutor.vo.RecentAnswerAnalysisVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

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

    @GetMapping("/knowledge-points")
    public Result<List<KnowledgePointProgressVO>> knowledgePointProgress() {
        return Result.success(learningAnalysisService.knowledgePointProgress());
    }

    @GetMapping("/recent-answers")
    public Result<List<RecentAnswerAnalysisVO>> recentAnswers(
            @RequestParam(defaultValue = "10") Integer limit) {
        return Result.success(learningAnalysisService.recentAnswers(limit));
    }
}
