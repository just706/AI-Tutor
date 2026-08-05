package com.aitutor.controller;

import com.aitutor.common.Result;
import com.aitutor.dto.GenerateAiPathRequest;
import com.aitutor.service.AiPathService;
import com.aitutor.vo.AiGeneratedPathVO;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ai/path")
public class AiPathController {

    private final AiPathService aiPathService;

    public AiPathController(AiPathService aiPathService) {
        this.aiPathService = aiPathService;
    }

    @PostMapping("/generate")
    public Result<AiGeneratedPathVO> generate(@Valid @RequestBody GenerateAiPathRequest request) {
        return Result.success(aiPathService.generate(request));
    }
}
