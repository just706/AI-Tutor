package com.aitutor.service;

import com.aitutor.dto.KnowledgePointRequest;
import com.aitutor.vo.KnowledgePointVO;

import java.util.List;

public interface KnowledgePointService {

    List<KnowledgePointVO> listTree(String subject);

    KnowledgePointVO create(KnowledgePointRequest request);

    KnowledgePointVO update(Long id, KnowledgePointRequest request);

    Boolean delete(Long id);
}
