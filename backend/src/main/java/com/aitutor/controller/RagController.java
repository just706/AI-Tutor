package com.aitutor.controller;

import com.aitutor.common.Result;
import com.aitutor.dto.RagChatRequest;
import com.aitutor.service.RagService;
import com.aitutor.vo.RagChatVO;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ai/rag")
public class RagController {

    private final RagService ragService;

    public RagController(RagService ragService) {
        this.ragService = ragService;
    }

    @PostMapping("/chat")
    public Result<RagChatVO> chat(@Valid @RequestBody RagChatRequest request) {
        return Result.success(ragService.chat(request));
    }
}
