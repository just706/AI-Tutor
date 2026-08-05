package com.aitutor.service;

import com.aitutor.dto.AiChatRequest;
import com.aitutor.vo.OrchestratorChatVO;

public interface TutorOrchestratorService {

    OrchestratorChatVO chat(AiChatRequest request);
}
