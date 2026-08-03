package com.aitutor.controller;

import com.aitutor.common.Result;
import com.aitutor.dto.AgentSuggestionStatusRequest;
import com.aitutor.dto.GenerateAgentSuggestionsRequest;
import com.aitutor.service.AgentService;
import com.aitutor.vo.AgentEventLogVO;
import com.aitutor.vo.AgentSuggestionVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/agents")
public class AgentController {

    private final AgentService agentService;

    public AgentController(AgentService agentService) {
        this.agentService = agentService;
    }

    @PostMapping("/suggestions/generate")
    public Result<List<AgentSuggestionVO>> generate(@RequestBody(required = false) GenerateAgentSuggestionsRequest request) {
        return Result.success(agentService.generateSuggestions(
                request == null ? new GenerateAgentSuggestionsRequest() : request));
    }

    @GetMapping("/suggestions")
    public Result<List<AgentSuggestionVO>> list(@RequestParam(required = false) String status,
                                                @RequestParam(required = false) String agentType) {
        return Result.success(agentService.listSuggestions(status, agentType));
    }

    @GetMapping("/suggestions/{suggestionId}/events")
    public Result<List<AgentEventLogVO>> events(@PathVariable Long suggestionId) {
        return Result.success(agentService.listEvents(suggestionId));
    }

    @PostMapping("/suggestions/{suggestionId}/confirm")
    public Result<AgentSuggestionVO> confirm(@PathVariable Long suggestionId,
                                             @RequestBody(required = false) AgentSuggestionStatusRequest request) {
        return Result.success(agentService.confirm(suggestionId,
                request == null ? new AgentSuggestionStatusRequest() : request));
    }

    @PostMapping("/suggestions/{suggestionId}/complete")
    public Result<AgentSuggestionVO> complete(@PathVariable Long suggestionId,
                                              @RequestBody(required = false) AgentSuggestionStatusRequest request) {
        return Result.success(agentService.complete(suggestionId,
                request == null ? new AgentSuggestionStatusRequest() : request));
    }

    @PostMapping("/suggestions/{suggestionId}/dismiss")
    public Result<AgentSuggestionVO> dismiss(@PathVariable Long suggestionId,
                                             @RequestBody(required = false) AgentSuggestionStatusRequest request) {
        return Result.success(agentService.dismiss(suggestionId,
                request == null ? new AgentSuggestionStatusRequest() : request));
    }
}
