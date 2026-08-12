package com.aitutor.service;

import com.aitutor.vo.LearningSessionVO;

public interface LearningSessionService {

    LearningSessionVO getActiveSession(Long conversationId);
}
