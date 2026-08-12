package com.aitutor.service;

import com.aitutor.entity.LearnerMemory;
import com.aitutor.entity.LearningSession;
import com.aitutor.vo.LearnerMemoryVO;

import java.util.List;

public interface LearnerMemoryService {

    List<LearnerMemoryVO> getCurrentMemories();

    List<LearnerMemory> getActiveMemories(Long userId);

    List<String> observeTutorChat(Long userId,
                                  LearningSession session,
                                  String intent,
                                  String topic,
                                  String userMessage,
                                  String teachingStrategy);

    Boolean suppressCurrentMemory(Long memoryId);

    Boolean deleteCurrentMemory(Long memoryId);
}
