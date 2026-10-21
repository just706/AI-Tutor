package com.aitutor.vo;
public record ReviewResultVO(Long taskId, Long evidenceId, Integer score, Integer masteryBefore,
                             Integer masteryAfter, String feedback, boolean completed) {}
