package com.aitutor.service.impl;

import com.aitutor.entity.LearningEvidence;
import com.aitutor.entity.LearningRecord;
import com.aitutor.entity.User;
import com.aitutor.exception.BusinessException;
import com.aitutor.mapper.LearningEvidenceMapper;
import com.aitutor.mapper.LearningRecordMapper;
import com.aitutor.mapper.UserMapper;
import com.aitutor.security.UserContext;
import com.aitutor.vo.LearningEvidenceVO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;

@Service
public class LearningEvidenceService {
    private static final String RULE = "recent-evidence-v1";
    private static final Pattern SCORE = Pattern.compile(
            "(?im)^\\s*(?:#{1,6}\\s*)?(?:[-*]\\s*|\\d+[.、)]\\s*)?(?:\\*\\*)?(?:得分|score)(?:\\*\\*)?\\s*[:：]\\s*(?:\\*\\*)?(\\d{1,3})(?:\\*\\*)?\\s*(?:分|/\\s*100)?\\s*[。.]?\\s*$");
    private static final Pattern SCORE_LABEL = Pattern.compile("(?im)^\\s*(?:#{1,6}\\s*)?(?:[-*]\\s*|\\d+[.、)]\\s*)?(?:\\*\\*)?(?:得分|score)(?:\\*\\*)?\\s*[:：]");
    private static final Pattern INSUFFICIENT = Pattern.compile("资料不足|证据不足|无法评分|无法评价|无法判断|不能评分|不能评价|不足以评分|不足以评价");
    private final LearningEvidenceMapper evidence;
    private final LearningRecordMapper records;
    private final UserMapper users;
    private final ObjectMapper json;

    public LearningEvidenceService(LearningEvidenceMapper evidence, LearningRecordMapper records,
                                   UserMapper users, ObjectMapper json) {
        this.evidence = evidence; this.records = records; this.users = users; this.json = json;
    }

    // 所有计分入口先锁定同一用户，串行保护首次建档、去重及跨入口状态更新。
    @Transactional(propagation = Propagation.MANDATORY)
    public String lockAndKey(String sourceType, String scope, String answer) {
        Long userId = UserContext.getRequired().getId();
        if (users.selectOne(new LambdaQueryWrapper<User>().eq(User::getId, userId)
                .last("LIMIT 1 FOR UPDATE")) == null) throw new BusinessException(404, "User not found");
        try {
            String input = sourceType + "\n" + scope + "\n" + answer.trim();
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }

    public <T> T replay(String key, Class<T> type) {
        LearningEvidence found = evidence.selectOne(new LambdaQueryWrapper<LearningEvidence>()
                .eq(LearningEvidence::getUserId, UserContext.getRequired().getId())
                .eq(LearningEvidence::getSourceKey, key).last("LIMIT 1 FOR UPDATE"));
        if (found == null) return null;
        try { return json.readValue(found.getResponseJson(), type); }
        catch (JsonProcessingException | IllegalArgumentException ex) {
            throw new BusinessException(409, "已保存的评价暂时无法恢复，请刷新学习记录。");
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public LearningEvidence record(String key, Long knowledgeId, String sourceType, String assessmentType,
                                   Object snapshot, String answer, String feedback, Integer score, int studyMinutes) {
        Long userId = UserContext.getRequired().getId();
        LearningRecord record = records.selectOne(new LambdaQueryWrapper<LearningRecord>()
                .eq(LearningRecord::getUserId, userId).eq(LearningRecord::getKnowledgePointId, knowledgeId).last("LIMIT 1 FOR UPDATE"));
        int before = record == null || record.getMasteryLevel() == null ? 0 : record.getMasteryLevel();
        String status = record == null ? "not_started" : record.getLearningStatus();
        LearningEvidence latest = evidence.selectOne(new LambdaQueryWrapper<LearningEvidence>()
                .eq(LearningEvidence::getUserId, userId).eq(LearningEvidence::getKnowledgePointId, knowledgeId)
                .isNotNull(LearningEvidence::getScore).orderByDesc(LearningEvidence::getId).last("LIMIT 1 FOR UPDATE"));
        LocalDateTime now = LocalDateTime.now();
        int after = before;
        String reason = "未取得唯一有效分数，状态保持不变。";
        if (score != null) {
            if (score < 0 || score > 100) throw new IllegalArgumentException("Invalid score");
            int penalty = latest == null ? 0 : (int) Math.min(30,
                    Math.max(0, ChronoUnit.DAYS.between(latest.getCreateTime(), now)) / 7 * 5);
            int observation = "model".equals(assessmentType) ? Math.min(score, 84) : score;
            after = latest == null ? observation : (int) Math.round(0.4 * Math.max(0, before - penalty) + 0.6 * observation);
            if ("model".equals(assessmentType)) after = Math.min(after, 84);
            status = after >= 85 ? "mastered" : after >= 60 ? "completed" : "in_progress";
            reason = latest == null ? "首条可追溯评分建立基线；旧记录不当作已核实证据。"
                    : "近期证据占60%，此前状态占40%；间隔" + penalty + "分调整仅在新评分时应用。";
            if ("model".equals(assessmentType)) reason += "模型评价，工程状态上限84，未经独立事实核验。";
            if (record == null) {
                record = new LearningRecord(); record.setUserId(userId); record.setKnowledgePointId(knowledgeId);
                record.setStudyTime(0);
            }
            record.setMasteryLevel(after); record.setLearningStatus(status);
            record.setStudyTime((record.getStudyTime() == null ? 0 : record.getStudyTime()) + studyMinutes);
            record.setUpdateTime(now);
            if (record.getId() == null) records.insert(record); else records.updateById(record);
        }
        LearningEvidence item = new LearningEvidence();
        item.setUserId(userId); item.setKnowledgePointId(knowledgeId); item.setSourceKey(key);
        item.setSourceType(sourceType); item.setAssessmentType(assessmentType); item.setSnapshot(write(snapshot));
        item.setStudentAnswer(answer); item.setFeedback(feedback); item.setScore(score);
        item.setMasteryBefore(before); item.setMasteryAfter(after); item.setStatusAfter(status);
        item.setPriorEvidenceId(latest == null ? null : latest.getId());
        item.setRuleVersion(RULE); item.setReason(reason); item.setCreateTime(now);
        evidence.insert(item);
        return item;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void finish(LearningEvidence item, Object response) {
        item.setResponseJson(write(response)); evidence.updateById(item);
    }

    public List<LearningEvidenceVO> list(Long knowledgePointId, int limit) {
        var query = new LambdaQueryWrapper<LearningEvidence>()
                .eq(LearningEvidence::getUserId, UserContext.getRequired().getId());
        if (knowledgePointId != null) query.eq(LearningEvidence::getKnowledgePointId, knowledgePointId);
        return evidence.selectList(query.orderByDesc(LearningEvidence::getId)
                .last("LIMIT " + Math.max(1, Math.min(limit, 100)))).stream().map(LearningEvidenceVO::from).toList();
    }

    public static Integer modelScore(String text) {
        if (text == null || INSUFFICIENT.matcher(text).find()) return null;
        if (SCORE_LABEL.matcher(text).results().count() != 1) return null;
        var matcher = SCORE.matcher(text);
        if (!matcher.find()) return null;
        int score = Integer.parseInt(matcher.group(1));
        if (score > 100 || matcher.find()) return null;
        return score;
    }

    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("Cannot save learning evidence", ex); }
    }
}
