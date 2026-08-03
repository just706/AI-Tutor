package com.aitutor.service.impl;

import com.aitutor.dto.GenerateStudyPlanRequest;
import com.aitutor.entity.AnswerRecord;
import com.aitutor.entity.ChatHistory;
import com.aitutor.entity.KnowledgePoint;
import com.aitutor.entity.LearningRecord;
import com.aitutor.entity.Question;
import com.aitutor.entity.StudentProfile;
import com.aitutor.mapper.AnswerRecordMapper;
import com.aitutor.mapper.ChatHistoryMapper;
import com.aitutor.mapper.KnowledgePointMapper;
import com.aitutor.mapper.LearningRecordMapper;
import com.aitutor.mapper.QuestionMapper;
import com.aitutor.mapper.StudentProfileMapper;
import com.aitutor.security.UserContext;
import com.aitutor.service.LearningAnalysisService;
import com.aitutor.vo.LearningAnalysisOverviewVO;
import com.aitutor.vo.StudyPlanVO;
import com.aitutor.vo.WeakKnowledgePointVO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class LearningAnalysisServiceImpl implements LearningAnalysisService {

    private static final int WEAK_THRESHOLD = 70;
    private static final int MASTERED_THRESHOLD = 85;
    private static final String PERIOD_MONTH = "month";
    private static final String PERIOD_WEEK = "week";

    private final LearningRecordMapper learningRecordMapper;
    private final AnswerRecordMapper answerRecordMapper;
    private final QuestionMapper questionMapper;
    private final KnowledgePointMapper knowledgePointMapper;
    private final ChatHistoryMapper chatHistoryMapper;
    private final StudentProfileMapper studentProfileMapper;

    public LearningAnalysisServiceImpl(LearningRecordMapper learningRecordMapper,
                                       AnswerRecordMapper answerRecordMapper,
                                       QuestionMapper questionMapper,
                                       KnowledgePointMapper knowledgePointMapper,
                                       ChatHistoryMapper chatHistoryMapper,
                                       StudentProfileMapper studentProfileMapper) {
        this.learningRecordMapper = learningRecordMapper;
        this.answerRecordMapper = answerRecordMapper;
        this.questionMapper = questionMapper;
        this.knowledgePointMapper = knowledgePointMapper;
        this.chatHistoryMapper = chatHistoryMapper;
        this.studentProfileMapper = studentProfileMapper;
    }

    @Override
    public LearningAnalysisOverviewVO overview() {
        Long userId = UserContext.getRequired().getId();
        AnalysisData data = loadAnalysisData(userId);

        LearningAnalysisOverviewVO overview = new LearningAnalysisOverviewVO();
        overview.setLearnedCount(data.records.size());
        overview.setMasteredCount(countMastered(data.records));
        overview.setInProgressCount(countInProgress(data.records));
        overview.setAverageMasteryLevel(averageMasteryLevel(data.records));
        overview.setTotalStudyTime(totalStudyTime(data.records));
        overview.setAnsweredQuestionCount(data.answers.size());
        overview.setCorrectAnswerCount(countCorrectAnswers(data.answers));
        overview.setAnswerAccuracy(answerAccuracy(data.answers));
        overview.setChatMessageCount(countChatMessages(userId));
        overview.setWeakKnowledgePoints(findWeakKnowledgePoints(data));
        overview.setSuggestions(buildSuggestions(overview, data.profile));
        overview.setNextActions(buildNextActions(overview, data.profile));
        return overview;
    }

    @Override
    public StudyPlanVO generateStudyPlan(GenerateStudyPlanRequest request) {
        Long userId = UserContext.getRequired().getId();
        AnalysisData data = loadAnalysisData(userId);
        LearningAnalysisOverviewVO overview = overview();

        String period = normalizePeriod(request.getPeriod());
        String goal = firstNonBlank(request.getGoal(), profileGoal(data.profile), "巩固当前学习内容");
        List<String> focusPoints = focusKnowledgePoints(overview, data.profile);
        List<String> steps = PERIOD_MONTH.equals(period)
                ? buildMonthPlanSteps(goal, focusPoints, overview)
                : buildWeekPlanSteps(goal, focusPoints, overview);

        StudyPlanVO plan = new StudyPlanVO();
        plan.setTitle(buildPlanTitle(data.profile, period));
        plan.setPeriod(period);
        plan.setGoal(goal);
        plan.setEstimatedDays(PERIOD_MONTH.equals(period) ? 30 : 7);
        plan.setFocusKnowledgePoints(focusPoints);
        plan.setSteps(steps);
        plan.setPlanContent(numberedContent(steps));
        return plan;
    }

    private AnalysisData loadAnalysisData(Long userId) {
        List<LearningRecord> records = learningRecordMapper.selectList(new LambdaQueryWrapper<LearningRecord>()
                .eq(LearningRecord::getUserId, userId)
                .orderByDesc(LearningRecord::getUpdateTime)
                .orderByDesc(LearningRecord::getId));
        List<AnswerRecord> answers = answerRecordMapper.selectList(new LambdaQueryWrapper<AnswerRecord>()
                .eq(AnswerRecord::getUserId, userId)
                .orderByDesc(AnswerRecord::getCreateTime)
                .orderByDesc(AnswerRecord::getId));
        Map<Long, Question> questionMap = loadQuestionMap(answers);
        Map<Long, KnowledgePoint> knowledgePointMap = loadKnowledgePointMap(records, questionMap);
        StudentProfile profile = studentProfileMapper.selectOne(new LambdaQueryWrapper<StudentProfile>()
                .eq(StudentProfile::getUserId, userId)
                .last("LIMIT 1"));
        return new AnalysisData(records, answers, questionMap, knowledgePointMap, profile);
    }

    private Map<Long, Question> loadQuestionMap(List<AnswerRecord> answers) {
        Set<Long> questionIds = answers.stream()
                .map(AnswerRecord::getQuestionId)
                .filter(id -> id != null)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (questionIds.isEmpty()) {
            return Map.of();
        }
        return questionMapper.selectByIds(questionIds)
                .stream()
                .collect(Collectors.toMap(Question::getId, Function.identity(), (left, right) -> left));
    }

    private Map<Long, KnowledgePoint> loadKnowledgePointMap(List<LearningRecord> records, Map<Long, Question> questionMap) {
        Set<Long> knowledgePointIds = new LinkedHashSet<>();
        records.stream()
                .map(LearningRecord::getKnowledgePointId)
                .filter(id -> id != null)
                .forEach(knowledgePointIds::add);
        questionMap.values().stream()
                .map(Question::getKnowledgePointId)
                .filter(id -> id != null)
                .forEach(knowledgePointIds::add);
        if (knowledgePointIds.isEmpty()) {
            return Map.of();
        }
        return knowledgePointMapper.selectByIds(knowledgePointIds)
                .stream()
                .collect(Collectors.toMap(KnowledgePoint::getId, Function.identity(), (left, right) -> left));
    }

    private Integer countMastered(List<LearningRecord> records) {
        return (int) records.stream()
                .filter(record -> safe(record.getMasteryLevel()) >= MASTERED_THRESHOLD)
                .count();
    }

    private Integer countInProgress(List<LearningRecord> records) {
        return (int) records.stream()
                .filter(record -> safe(record.getMasteryLevel()) < MASTERED_THRESHOLD)
                .count();
    }

    private Integer averageMasteryLevel(List<LearningRecord> records) {
        if (records.isEmpty()) {
            return 0;
        }
        int sum = records.stream()
                .mapToInt(record -> safe(record.getMasteryLevel()))
                .sum();
        return Math.round((float) sum / records.size());
    }

    private Integer totalStudyTime(List<LearningRecord> records) {
        return records.stream()
                .mapToInt(record -> safe(record.getStudyTime()))
                .sum();
    }

    private Integer countCorrectAnswers(List<AnswerRecord> answers) {
        return (int) answers.stream()
                .filter(answer -> safe(answer.getIsCorrect()) == 1)
                .count();
    }

    private Integer answerAccuracy(List<AnswerRecord> answers) {
        if (answers.isEmpty()) {
            return 0;
        }
        return Math.round((float) countCorrectAnswers(answers) * 100 / answers.size());
    }

    private Integer countChatMessages(Long userId) {
        Long count = chatHistoryMapper.selectCount(new LambdaQueryWrapper<ChatHistory>()
                .eq(ChatHistory::getUserId, userId));
        return count == null ? 0 : Math.toIntExact(count);
    }

    private List<WeakKnowledgePointVO> findWeakKnowledgePoints(AnalysisData data) {
        Map<Long, WeakKnowledgePointVO> weakMap = new LinkedHashMap<>();
        for (LearningRecord record : data.records) {
            int masteryLevel = safe(record.getMasteryLevel());
            if (masteryLevel < WEAK_THRESHOLD) {
                WeakKnowledgePointVO weakPoint = toWeakKnowledgePoint(
                        record.getKnowledgePointId(), data.knowledgePointMap, masteryLevel, null, "掌握度低于 70%");
                weakMap.put(record.getKnowledgePointId(), weakPoint);
            }
        }

        // 答题记录能补足只靠 mastery_level 看不到的弱项，比如近期连续答错的知识点。
        Map<Long, PointAnswerStats> statsMap = buildPointAnswerStats(data.answers, data.questionMap);
        for (Map.Entry<Long, PointAnswerStats> entry : statsMap.entrySet()) {
            PointAnswerStats stats = entry.getValue();
            int accuracy = stats.accuracy();
            int averageScore = stats.averageScore();
            if (accuracy < WEAK_THRESHOLD || averageScore < WEAK_THRESHOLD) {
                WeakKnowledgePointVO weakPoint = weakMap.get(entry.getKey());
                if (weakPoint == null) {
                    weakPoint = toWeakKnowledgePoint(entry.getKey(), data.knowledgePointMap, averageScore,
                            accuracy, "答题正确率低于 70%");
                    weakMap.put(entry.getKey(), weakPoint);
                } else {
                    weakPoint.setAnswerAccuracy(accuracy);
                    weakPoint.setReason(weakPoint.getReason() + "；答题正确率低于 70%");
                }
            }
        }

        return weakMap.values().stream()
                .sorted(Comparator.comparingInt(this::weaknessScore)
                        .thenComparing(WeakKnowledgePointVO::getKnowledgePointId))
                .limit(5)
                .toList();
    }

    private Map<Long, PointAnswerStats> buildPointAnswerStats(List<AnswerRecord> answers, Map<Long, Question> questionMap) {
        Map<Long, PointAnswerStats> statsMap = new LinkedHashMap<>();
        for (AnswerRecord answer : answers) {
            Question question = questionMap.get(answer.getQuestionId());
            if (question == null || question.getKnowledgePointId() == null) {
                continue;
            }
            statsMap.computeIfAbsent(question.getKnowledgePointId(), key -> new PointAnswerStats())
                    .add(answer);
        }
        return statsMap;
    }

    private WeakKnowledgePointVO toWeakKnowledgePoint(Long knowledgePointId,
                                                      Map<Long, KnowledgePoint> knowledgePointMap,
                                                      Integer masteryLevel,
                                                      Integer answerAccuracy,
                                                      String reason) {
        KnowledgePoint point = knowledgePointMap.get(knowledgePointId);
        String name = point == null ? "知识点 #" + knowledgePointId : point.getName();
        String subject = point == null ? null : point.getSubject();
        return new WeakKnowledgePointVO(knowledgePointId, name, subject, masteryLevel, answerAccuracy, reason);
    }

    private Integer weaknessScore(WeakKnowledgePointVO weakPoint) {
        int mastery = weakPoint.getMasteryLevel() == null ? 100 : weakPoint.getMasteryLevel();
        int accuracy = weakPoint.getAnswerAccuracy() == null ? 100 : weakPoint.getAnswerAccuracy();
        return Math.min(mastery, accuracy);
    }

    private List<String> buildSuggestions(LearningAnalysisOverviewVO overview, StudentProfile profile) {
        List<String> suggestions = new ArrayList<>();
        if (profile == null || isBlank(profile.getLearningGoal()) || isBlank(profile.getCurrentLevel())) {
            suggestions.add("先完善学习目标和当前水平，后续计划会更贴合你的情况。");
        }
        if (overview.getLearnedCount() == 0) {
            suggestions.add("先选择一个知识点进入教学，再通过练习建立第一条学习记录。");
        }
        if (!overview.getWeakKnowledgePoints().isEmpty()) {
            suggestions.add("优先复习：" + weakPointNames(overview.getWeakKnowledgePoints()) + "。");
        }
        if (overview.getAnsweredQuestionCount() == 0) {
            suggestions.add("每个知识点先生成 3 道中等难度题，建立可分析的答题样本。");
        } else if (overview.getAnswerAccuracy() < WEAK_THRESHOLD) {
            suggestions.add("当前答题正确率低于 70%，建议先复盘错题解析，再做同知识点变式题。");
        }
        if (overview.getAverageMasteryLevel() >= MASTERED_THRESHOLD) {
            suggestions.add("整体掌握度较高，可以切换到更高难度题或开启新知识点。");
        }
        if (suggestions.isEmpty()) {
            suggestions.add("保持当前学习节奏，每完成一轮教学和练习后回来查看变化。");
        }
        return suggestions;
    }

    private List<String> buildNextActions(LearningAnalysisOverviewVO overview, StudentProfile profile) {
        List<String> actions = new ArrayList<>();
        if (!overview.getWeakKnowledgePoints().isEmpty()) {
            String names = weakPointNames(overview.getWeakKnowledgePoints());
            actions.add("用教学模式重新学习：" + names + "。");
            actions.add("围绕 " + names + " 各完成 2 道客观题和 1 道简答题。");
        } else {
            actions.add("选择一个还没学过的知识点，完成一次教学和一次练习。");
        }
        if (profile != null && !isBlank(profile.getLearningPreference())) {
            actions.add("按你的偏好「" + profile.getLearningPreference() + "」整理一页复习笔记。");
        }
        actions.add("完成练习后重新查看学习分析，确认掌握度是否提升。");
        return actions;
    }

    private List<String> focusKnowledgePoints(LearningAnalysisOverviewVO overview, StudentProfile profile) {
        List<String> weakNames = overview.getWeakKnowledgePoints().stream()
                .map(WeakKnowledgePointVO::getKnowledgePointName)
                .filter(name -> !isBlank(name))
                .limit(3)
                .toList();
        if (!weakNames.isEmpty()) {
            return weakNames;
        }
        String direction = profile == null ? null : profile.getLearningDirection();
        if (!isBlank(direction)) {
            return List.of(direction);
        }
        return List.of("当前知识点");
    }

    private List<String> buildWeekPlanSteps(String goal, List<String> focusPoints, LearningAnalysisOverviewVO overview) {
        String focus = String.join("、", focusPoints);
        List<String> steps = new ArrayList<>();
        steps.add("第 1 天：明确目标「" + goal + "」，浏览 " + focus + " 的知识点结构。");
        steps.add("第 2-3 天：使用教学模式重学 " + focus + "，把不懂的问题继续追问。");
        steps.add("第 4-5 天：围绕 " + focus + " 完成客观题练习，错题必须阅读解析。");
        steps.add("第 6 天：完成 1-2 道简答题，用反馈检查表达和理解深度。");
        steps.add("第 7 天：复盘学习记录，目标是掌握度提升到 " + targetMastery(overview) + "% 以上。");
        return steps;
    }

    private List<String> buildMonthPlanSteps(String goal, List<String> focusPoints, LearningAnalysisOverviewVO overview) {
        String focus = String.join("、", focusPoints);
        List<String> steps = new ArrayList<>();
        steps.add("第 1 周：围绕目标「" + goal + "」梳理学习档案，确定重点：" + focus + "。");
        steps.add("第 2 周：用教学模式逐个突破重点知识点，每次学习后保存理解检查反馈。");
        steps.add("第 3 周：进行分层练习，先做中等题，再补困难题，正确率目标 " + targetAccuracy(overview) + "%。");
        steps.add("第 4 周：整理错题和资料问答记录，形成一份可复习的知识清单。");
        steps.add("月底：重新查看学习分析，用薄弱知识点列表决定下一轮计划。");
        return steps;
    }

    private String buildPlanTitle(StudentProfile profile, String period) {
        String direction = profile == null ? null : profile.getLearningDirection();
        String prefix = isBlank(direction) ? "AI Tutor" : direction.trim();
        return prefix + (PERIOD_MONTH.equals(period) ? " 月度提升计划" : " 周学习计划");
    }

    private String numberedContent(List<String> steps) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < steps.size(); i++) {
            if (i > 0) {
                builder.append('\n');
            }
            builder.append(i + 1).append(". ").append(steps.get(i));
        }
        return builder.toString();
    }

    private String normalizePeriod(String period) {
        if (isBlank(period)) {
            return PERIOD_WEEK;
        }
        String normalized = period.trim().toLowerCase(Locale.ROOT);
        return PERIOD_MONTH.equals(normalized) ? PERIOD_MONTH : PERIOD_WEEK;
    }

    private Integer targetMastery(LearningAnalysisOverviewVO overview) {
        return Math.max(75, Math.min(90, overview.getAverageMasteryLevel() + 15));
    }

    private Integer targetAccuracy(LearningAnalysisOverviewVO overview) {
        return Math.max(75, Math.min(90, overview.getAnswerAccuracy() + 15));
    }

    private String weakPointNames(List<WeakKnowledgePointVO> weakPoints) {
        return weakPoints.stream()
                .map(WeakKnowledgePointVO::getKnowledgePointName)
                .filter(name -> !isBlank(name))
                .limit(3)
                .collect(Collectors.joining("、"));
    }

    private String profileGoal(StudentProfile profile) {
        return profile == null ? null : profile.getLearningGoal();
    }

    private String firstNonBlank(String first, String second, String fallback) {
        if (!isBlank(first)) {
            return first.trim();
        }
        if (!isBlank(second)) {
            return second.trim();
        }
        return fallback;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private int safe(Integer value) {
        return value == null ? 0 : value;
    }

    private record AnalysisData(List<LearningRecord> records,
                                List<AnswerRecord> answers,
                                Map<Long, Question> questionMap,
                                Map<Long, KnowledgePoint> knowledgePointMap,
                                StudentProfile profile) {
    }

    private static class PointAnswerStats {

        private int answered;
        private int correct;
        private int scoreSum;

        void add(AnswerRecord answer) {
            answered++;
            if (answer.getIsCorrect() != null && answer.getIsCorrect() == 1) {
                correct++;
            }
            scoreSum += answer.getScore() == null ? 0 : answer.getScore();
        }

        int accuracy() {
            if (answered == 0) {
                return 0;
            }
            return Math.round((float) correct * 100 / answered);
        }

        int averageScore() {
            if (answered == 0) {
                return 0;
            }
            return Math.round((float) scoreSum / answered);
        }
    }
}
