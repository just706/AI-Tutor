package com.aitutor.controller;

import com.aitutor.common.Result;
import com.aitutor.dto.CreateConversationRequest;
import com.aitutor.dto.UpdateConversationDocumentsRequest;
import com.aitutor.service.ConversationService;
import com.aitutor.vo.ChatMessageVO;
import com.aitutor.vo.ConversationCreateVO;
import com.aitutor.vo.ConversationVO;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/conversations")
public class ConversationController {

    private final ConversationService conversationService;

    public ConversationController(ConversationService conversationService) {
        this.conversationService = conversationService;
    }

    @PostMapping
    public Result<ConversationCreateVO> createConversation(@Valid @RequestBody CreateConversationRequest request) {
        return Result.success(conversationService.createConversation(request));
    }

    @GetMapping
    public Result<List<ConversationVO>> listConversations() {
        return Result.success(conversationService.listCurrentUserConversations());
    }

    @GetMapping("/{conversationId}/messages")
    public Result<List<ChatMessageVO>> listMessages(@PathVariable Long conversationId) {
        return Result.success(conversationService.listCurrentUserMessages(conversationId));
    }

    @GetMapping("/{conversationId}")
    public Result<ConversationVO> getConversation(@PathVariable Long conversationId) {
        return Result.success(conversationService.getCurrentUserConversation(conversationId));
    }

    @PutMapping("/{conversationId}/documents")
    public Result<ConversationVO> updateDocuments(@PathVariable Long conversationId,
            @Valid @RequestBody UpdateConversationDocumentsRequest request) {
        return Result.success(conversationService.updateDocuments(conversationId, request.getDocumentIds()));
    }
}
