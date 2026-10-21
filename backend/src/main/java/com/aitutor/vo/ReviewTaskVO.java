package com.aitutor.vo;
import java.time.LocalDateTime;
import java.util.List;

public record ReviewTaskVO(Long id, Long knowledgePointId, String knowledgePointName,
                           Long sourceEvidenceId, Long resultEvidenceId, String assessmentType,
                           String questionType, String question, List<String> options, String status,
                           LocalDateTime dueTime, LocalDateTime completedTime, ReviewResultVO result) {}
