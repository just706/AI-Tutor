package com.aitutor.service;

import com.aitutor.entity.LearningSession;
import com.aitutor.entity.LearningSessionStep;
import com.aitutor.vo.TeachingStrategyDecisionVO;

public interface TeachingStrategyService {

    TeachingStrategyDecisionVO decide(String intent,
                                      String userMessage,
                                      LearningSession learningSession,
                                      String currentTopic,
                                      LearningSessionStep recentStep);
}
