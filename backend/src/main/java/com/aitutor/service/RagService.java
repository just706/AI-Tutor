package com.aitutor.service;

import com.aitutor.dto.RagChatRequest;
import com.aitutor.vo.RagChatVO;

public interface RagService {

    RagChatVO chat(RagChatRequest request);
}
