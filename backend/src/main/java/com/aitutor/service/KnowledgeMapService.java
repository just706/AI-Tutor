package com.aitutor.service;

import com.aitutor.vo.KnowledgeMapContextVO;
import com.aitutor.vo.KnowledgeGraphVO;

public interface KnowledgeMapService {

    KnowledgeMapContextVO resolve(Long userId, String topic);

    KnowledgeGraphVO graph(String subject);
}
