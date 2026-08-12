package com.aitutor.controller;

import com.aitutor.common.Result;
import com.aitutor.service.LearningSessionService;
import com.aitutor.vo.LearningSessionVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/learning-sessions")
public class LearningSessionController {

    private final LearningSessionService learningSessionService;

    public LearningSessionController(LearningSessionService learningSessionService) {
        this.learningSessionService = learningSessionService;
    }

    @GetMapping("/active")
    public Result<LearningSessionVO> getActiveSession(@RequestParam Long conversationId) {
        return Result.success(learningSessionService.getActiveSession(conversationId));
    }
}
