package com.aitutor.controller;

import com.aitutor.common.Result;
import com.aitutor.dto.EvaluateTeachingRequest;
import com.aitutor.dto.StartTeachingRequest;
import com.aitutor.service.TeachingService;
import com.aitutor.vo.LearningRecordVO;
import com.aitutor.vo.TeachingEvaluationVO;
import com.aitutor.vo.TeachingStartVO;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/teaching")
public class TeachingController {

    private final TeachingService teachingService;

    public TeachingController(TeachingService teachingService) {
        this.teachingService = teachingService;
    }

    @PostMapping("/start")
    public Result<TeachingStartVO> start(@Valid @RequestBody StartTeachingRequest request) {
        return Result.success(teachingService.start(request));
    }

    @PostMapping("/evaluate")
    public Result<TeachingEvaluationVO> evaluate(@Valid @RequestBody EvaluateTeachingRequest request) {
        return Result.success(teachingService.evaluate(request));
    }

    @GetMapping("/records")
    public Result<List<LearningRecordVO>> listRecords() {
        return Result.success(teachingService.listCurrentUserRecords());
    }
}
