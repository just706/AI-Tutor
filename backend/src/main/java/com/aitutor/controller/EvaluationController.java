package com.aitutor.controller;

import com.aitutor.common.Result;
import com.aitutor.service.EvaluationService;
import com.aitutor.vo.EvaluationOverviewVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/evaluation")
public class EvaluationController {

    private final EvaluationService evaluationService;

    public EvaluationController(EvaluationService evaluationService) {
        this.evaluationService = evaluationService;
    }

    @GetMapping("/overview")
    public Result<EvaluationOverviewVO> overview() {
        return Result.success(evaluationService.overview());
    }
}
