package com.aitutor.controller;

import com.aitutor.common.Result;
import com.aitutor.dto.AiChatRequest;
import com.aitutor.service.TutorOrchestratorService;
import com.aitutor.vo.OrchestratorChatVO;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ai/orchestrator")
public class TutorOrchestratorController {

    private final TutorOrchestratorService tutorOrchestratorService;

    public TutorOrchestratorController(TutorOrchestratorService tutorOrchestratorService) {
        this.tutorOrchestratorService = tutorOrchestratorService;
    }

    @PostMapping("/chat")
    public Result<OrchestratorChatVO> chat(@Valid @RequestBody AiChatRequest request) {
        return Result.success(tutorOrchestratorService.chat(request));
    }
}
