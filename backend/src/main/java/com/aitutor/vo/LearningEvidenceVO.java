package com.aitutor.vo;

import com.aitutor.entity.LearningEvidence;
import java.time.LocalDateTime;

public record LearningEvidenceVO(Long id, Long knowledgePointId, String sourceType, String assessmentType,
                                 String snapshot, String studentAnswer, String feedback, Integer score,
                                 Integer masteryBefore, Integer masteryAfter, Long priorEvidenceId, String statusAfter,
                                 String ruleVersion, String reason, LocalDateTime createTime) {
    public static LearningEvidenceVO from(LearningEvidence item) {
        return new LearningEvidenceVO(item.getId(), item.getKnowledgePointId(), item.getSourceType(),
                item.getAssessmentType(), item.getSnapshot(), item.getStudentAnswer(), item.getFeedback(), item.getScore(),
                item.getMasteryBefore(), item.getMasteryAfter(), item.getPriorEvidenceId(), item.getStatusAfter(), item.getRuleVersion(),
                item.getReason(), item.getCreateTime());
    }
}
