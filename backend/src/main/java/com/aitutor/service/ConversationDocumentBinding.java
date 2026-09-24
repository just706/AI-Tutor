package com.aitutor.service;

import com.aitutor.entity.Conversation;
import com.aitutor.entity.LearningDocument;
import com.aitutor.exception.BusinessException;
import com.aitutor.mapper.LearningDocumentMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class ConversationDocumentBinding {
    private final LearningDocumentMapper documentMapper;
    private final ObjectMapper objectMapper;

    public ConversationDocumentBinding(LearningDocumentMapper documentMapper, ObjectMapper objectMapper) {
        this.documentMapper = documentMapper;
        this.objectMapper = objectMapper;
    }

    public List<Long> documentIds(Conversation conversation) {
        String stored = conversation.getDocumentIds();
        if (stored == null || stored.isBlank()) return List.of();
        try {
            List<Long> ids = objectMapper.readValue(stored, new TypeReference<List<Long>>() {});
            if (ids == null || ids.size() > 20 || ids.stream().anyMatch(id -> id == null || id <= 0)) {
                throw new BusinessException(500, "会话教材记录无效，请重新选择教材");
            }
            return List.copyOf(new LinkedHashSet<>(ids));
        } catch (JsonProcessingException ex) {
            throw new BusinessException(500, "会话教材记录无效，请重新选择教材");
        }
    }

    public List<LearningDocument> validateDocuments(Long userId, List<Long> documentIds) {
        if (documentIds == null || documentIds.size() > 20
                || documentIds.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new BusinessException(400, "请选择最多 20 份有效教材");
        }
        List<Long> ids = List.copyOf(new LinkedHashSet<>(documentIds));
        if (ids.isEmpty()) return List.of();
        Map<Long, LearningDocument> documents = documentMapper.selectList(new LambdaQueryWrapper<LearningDocument>()
                        .eq(LearningDocument::getUserId, userId)
                        .in(LearningDocument::getId, ids))
                .stream().collect(Collectors.toMap(LearningDocument::getId, Function.identity()));
        // 整个选择必须有效，不能因部分教材删除或越权而悄悄扩大、缩小检索范围。
        if (ids.stream().anyMatch(id -> !documents.containsKey(id))) {
            throw new BusinessException(404, "部分教材已删除或不可访问，请重新选择教材");
        }
        if (ids.stream().anyMatch(id -> !"completed".equals(documents.get(id).getProcessStatus()))) {
            throw new BusinessException(409, "部分教材尚未完成处理，请重新选择教材或等待处理完成");
        }
        return ids.stream().map(documents::get).toList();
    }

    public String serialize(List<LearningDocument> documents) {
        try {
            return objectMapper.writeValueAsString(documents.stream().map(LearningDocument::getId).toList());
        } catch (JsonProcessingException ex) {
            throw new BusinessException(500, "无法保存会话教材");
        }
    }
}
