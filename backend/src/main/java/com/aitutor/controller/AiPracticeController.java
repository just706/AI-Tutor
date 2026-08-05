package com.aitutor.controller;

import com.aitutor.common.Result;
import com.aitutor.dto.GenerateAiPracticeRequest;
import com.aitutor.service.AiPracticeService;
import com.aitutor.vo.AiPracticeVO;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ai/practice")
public class AiPracticeController {

    private final AiPracticeService aiPracticeService;

    public AiPracticeController(AiPracticeService aiPracticeService) {
        this.aiPracticeService = aiPracticeService;
    }

    @PostMapping("/generate")
    public Result<AiPracticeVO> generate(@Valid @RequestBody GenerateAiPracticeRequest request) {
        return Result.success(aiPracticeService.generate(request));
    }
}
