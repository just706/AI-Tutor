package com.aitutor.controller;

import com.aitutor.common.Result;
import com.aitutor.dto.TutorAgentChatRequest;
import com.aitutor.service.TutorAgentService;
import com.aitutor.vo.TutorAgentChatVO;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tutor-agent")
public class TutorAgentController {

    private final TutorAgentService tutorAgentService;

    public TutorAgentController(TutorAgentService tutorAgentService) {
        this.tutorAgentService = tutorAgentService;
    }

    @PostMapping("/chat")
    public Result<TutorAgentChatVO> chat(@Valid @RequestBody TutorAgentChatRequest request) {
        return Result.success(tutorAgentService.chat(request));
    }
}
