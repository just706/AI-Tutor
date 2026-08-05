package com.aitutor.service;

import com.aitutor.dto.GenerateAiPathRequest;
import com.aitutor.vo.AiGeneratedPathVO;

public interface AiPathService {

    AiGeneratedPathVO generate(GenerateAiPathRequest request);
}
