package com.aitutor.service;

import com.aitutor.vo.KnowledgeMapContextVO;

public interface KnowledgeMapService {

    KnowledgeMapContextVO resolve(Long userId, String topic);
}
