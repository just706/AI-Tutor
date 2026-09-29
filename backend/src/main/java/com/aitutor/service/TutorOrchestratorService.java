package com.aitutor.service;

import com.aitutor.dto.AiChatRequest;
import com.aitutor.ai.TutorChatPlan;
import com.aitutor.ai.TutorTurnContext;
import com.aitutor.vo.OrchestratorChatVO;

public interface TutorOrchestratorService {

    OrchestratorChatVO chat(AiChatRequest request);

    TutorChatPlan plan(String message);

    OrchestratorChatVO chat(AiChatRequest request, TutorChatPlan plan, TutorTurnContext context);
}
