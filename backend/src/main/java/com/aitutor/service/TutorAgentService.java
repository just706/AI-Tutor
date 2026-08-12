package com.aitutor.service;

import com.aitutor.dto.TutorAgentChatRequest;
import com.aitutor.vo.TutorAgentChatVO;

public interface TutorAgentService {

    TutorAgentChatVO chat(TutorAgentChatRequest request);
}
