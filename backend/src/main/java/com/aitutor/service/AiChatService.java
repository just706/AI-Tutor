package com.aitutor.service;

import com.aitutor.dto.AiChatRequest;
import com.aitutor.vo.AiChatVO;

public interface AiChatService {

    AiChatVO chat(AiChatRequest request);
}
