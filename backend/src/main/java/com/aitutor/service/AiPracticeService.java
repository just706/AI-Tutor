package com.aitutor.service;

import com.aitutor.dto.GenerateAiPracticeRequest;
import com.aitutor.vo.AiPracticeVO;

public interface AiPracticeService {

    AiPracticeVO generate(GenerateAiPracticeRequest request);
}
