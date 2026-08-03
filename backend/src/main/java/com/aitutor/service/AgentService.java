package com.aitutor.service;

import com.aitutor.dto.AgentSuggestionStatusRequest;
import com.aitutor.dto.GenerateAgentSuggestionsRequest;
import com.aitutor.vo.AgentEventLogVO;
import com.aitutor.vo.AgentSuggestionVO;

import java.util.List;

public interface AgentService {

    List<AgentSuggestionVO> generateSuggestions(GenerateAgentSuggestionsRequest request);

    List<AgentSuggestionVO> listSuggestions(String status, String agentType);

    List<AgentEventLogVO> listEvents(Long suggestionId);

    AgentSuggestionVO confirm(Long suggestionId, AgentSuggestionStatusRequest request);

    AgentSuggestionVO complete(Long suggestionId, AgentSuggestionStatusRequest request);

    AgentSuggestionVO dismiss(Long suggestionId, AgentSuggestionStatusRequest request);
}
