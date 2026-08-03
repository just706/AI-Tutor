package com.aitutor.service.impl;

import com.aitutor.ai.AiChatResult;
import com.aitutor.ai.AiMessage;
import com.aitutor.ai.AiPromptBuilder;
import com.aitutor.ai.DeepSeekClient;
import com.aitutor.dto.GenerateQuestionsRequest;
import com.aitutor.dto.SubmitAnswerRequest;
import com.aitutor.entity.AiCallLog;
import com.aitutor.entity.AnswerRecord;
import com.aitutor.entity.KnowledgePoint;
import com.aitutor.entity.LearningRecord;
import com.aitutor.entity.Question;
import com.aitutor.entity.StudentProfile;
import com.aitutor.exception.AiServiceException;
import com.aitutor.exception.BusinessException;
import com.aitutor.mapper.AiCallLogMapper;
import com.aitutor.mapper.AnswerRecordMapper;
import com.aitutor.mapper.KnowledgePointMapper;
import com.aitutor.mapper.LearningRecordMapper;
import com.aitutor.mapper.QuestionMapper;
import com.aitutor.mapper.StudentProfileMapper;
import com.aitutor.security.UserContext;
import com.aitutor.service.QuestionService;
import com.aitutor.vo.AnswerResultVO;
import com.aitutor.vo.QuestionVO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class QuestionServiceImpl implements QuestionService {

    private static final String ROLE_SYSTEM = "system";
    private static final String ROLE_USER = "user";
    private static final String PROVIDER_DEEPSEEK = "DeepSeek";
    private static final String REQUEST_TYPE_QUESTION_GENERATE = "question_generate";
    private static final String REQUEST_TYPE_ANSWER_EVALUATE = "answer_evaluate";
    private static final String SOURCE_AI = "ai";
    private static final String TYPE_SINGLE_CHOICE = "single_choice";
    private static final String TYPE_TRUE_FALSE = "true_false";
    private static final String TYPE_SHORT_ANSWER = "short_answer";
    private static final String DIFFICULTY_MEDIUM = "medium";
    private static final String STATUS_IN_PROGRESS = "in_progress";
    private static final String STATUS_COMPLETED = "completed";
    private static final String STATUS_MASTERED = "mastered";
    private static final Set<String> SUPPORTED_TYPES = Set.of(TYPE_SINGLE_CHOICE, TYPE_TRUE_FALSE, TYPE_SHORT_ANSWER);
    private static final Set<String> SUPPORTED_DIFFICULTIES = Set.of("easy", DIFFICULTY_MEDIUM, "hard");
    private static final Pattern CHINESE_SCORE_PATTERN = Pattern.compile("\\u5f97\\u5206\\D{0,10}(\\d{1,3})");
    private static final Pattern ENGLISH_SCORE_PATTERN = Pattern.compile("(?i)score\\D{0,10}(\\d{1,3})");
    private static final Pattern POINT_SCORE_PATTERN = Pattern.compile("(\\d{1,3})\\s*\\u5206");

    private final QuestionMapper questionMapper;
    private final AnswerRecordMapper answerRecordMapper;
    private final KnowledgePointMapper knowledgePointMapper;
    private final LearningRecordMapper learningRecordMapper;
    private final StudentProfileMapper studentProfileMapper;
    private final AiCallLogMapper aiCallLogMapper;
    private final DeepSeekClient deepSeekClient;
    private final AiPromptBuilder aiPromptBuilder;
    private final ObjectMapper objectMapper;

    public QuestionServiceImpl(QuestionMapper questionMapper,
                               AnswerRecordMapper answerRecordMapper,
                               KnowledgePointMapper knowledgePointMapper,
                               LearningRecordMapper learningRecordMapper,
                               StudentProfileMapper studentProfileMapper,
                               AiCallLogMapper aiCallLogMapper,
                               DeepSeekClient deepSeekClient,
                               AiPromptBuilder aiPromptBuilder,
                               ObjectMapper objectMapper) {
        this.questionMapper = questionMapper;
        this.answerRecordMapper = answerRecordMapper;
        this.knowledgePointMapper = knowledgePointMapper;
        this.learningRecordMapper = learningRecordMapper;
        this.studentProfileMapper = studentProfileMapper;
        this.aiCallLogMapper = aiCallLogMapper;
        this.deepSeekClient = deepSeekClient;
        this.aiPromptBuilder = aiPromptBuilder;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional(noRollbackFor = AiServiceException.class)
    public List<QuestionVO> generate(GenerateQuestionsRequest request) {
        Long userId = UserContext.getRequired().getId();
        KnowledgePoint knowledgePoint = requireKnowledgePoint(request.getKnowledgePointId());
        StudentProfile profile = findProfile(userId);
        String questionType = normalizeQuestionType(request.getQuestionType());
        String difficulty = normalizeDifficulty(request.getDifficulty());
        int count = normalizeCount(request.getCount());

        List<AiMessage> messages = List.of(
                new AiMessage(ROLE_SYSTEM,
                        aiPromptBuilder.buildQuestionGenerationPrompt(profile, knowledgePoint, questionType, difficulty, count)),
                new AiMessage(ROLE_USER, "请生成练习题。")
        );

        AiChatResult result = null;
        try {
            result = deepSeekClient.chat(messages);
            // Validate the full AI JSON payload before inserting any question rows.
            List<GeneratedQuestion> generatedQuestions = parseGeneratedQuestions(result.getContent(), count);
            List<Question> savedQuestions = new ArrayList<>();
            for (GeneratedQuestion generatedQuestion : generatedQuestions) {
                Question question = toQuestion(knowledgePoint.getId(), questionType, difficulty, generatedQuestion);
                questionMapper.insert(question);
                savedQuestions.add(question);
            }
            saveAiCallLog(userId, REQUEST_TYPE_QUESTION_GENERATE, result.getPromptTokens(),
                    result.getCompletionTokens(), "success", null);
            return savedQuestions.stream()
                    .map(question -> QuestionVO.from(question, knowledgePoint.getName(), optionsFromJson(question.getOptions())))
                    .toList();
        } catch (AiServiceException ex) {
            Integer promptTokens = result == null ? null : result.getPromptTokens();
            Integer completionTokens = result == null ? null : result.getCompletionTokens();
            saveAiCallLog(userId, REQUEST_TYPE_QUESTION_GENERATE, promptTokens, completionTokens, "failed", ex.getMessage());
            throw ex;
        }
    }

    @Override
    public List<QuestionVO> list(Long knowledgePointId, String questionType, String difficulty) {
        LambdaQueryWrapper<Question> wrapper = new LambdaQueryWrapper<Question>()
                .orderByDesc(Question::getCreateTime)
                .orderByDesc(Question::getId);
        if (knowledgePointId != null) {
            wrapper.eq(Question::getKnowledgePointId, knowledgePointId);
        }
        if (questionType != null && !questionType.trim().isEmpty()) {
            wrapper.eq(Question::getQuestionType, normalizeQuestionType(questionType));
        }
        if (difficulty != null && !difficulty.trim().isEmpty()) {
            wrapper.eq(Question::getDifficulty, normalizeDifficulty(difficulty));
        }

        List<Question> questions = questionMapper.selectList(wrapper);
        return questions.stream()
                .map(question -> QuestionVO.from(question, knowledgePointName(question.getKnowledgePointId()),
                        optionsFromJson(question.getOptions())))
                .toList();
    }

    @Override
    public QuestionVO get(Long id) {
        Question question = requireQuestion(id);
        return QuestionVO.from(question, knowledgePointName(question.getKnowledgePointId()), optionsFromJson(question.getOptions()));
    }

    @Override
    @Transactional(noRollbackFor = AiServiceException.class)
    public AnswerResultVO answer(Long questionId, SubmitAnswerRequest request) {
        Long userId = UserContext.getRequired().getId();
        Question question = requireQuestion(questionId);
        KnowledgePoint knowledgePoint = requireKnowledgePoint(question.getKnowledgePointId());
        String studentAnswer = request.getAnswer().trim();

        if (isObjectiveQuestion(question.getQuestionType())) {
            // Objective questions are deterministic, so the backend scores them without another AI call.
            return evaluateObjectiveAnswer(userId, question, knowledgePoint, studentAnswer);
        }
        return evaluateSubjectiveAnswer(userId, question, knowledgePoint, studentAnswer);
    }

    private AnswerResultVO evaluateObjectiveAnswer(Long userId,
                                                   Question question,
                                                   KnowledgePoint knowledgePoint,
                                                   String studentAnswer) {
        boolean correct = answersEqual(question.getQuestionType(), question.getAnswer(), studentAnswer);
        int score = correct ? 100 : 0;
        String feedback = buildObjectiveFeedback(correct, question.getAnswer(), question.getAnalysis());
        AnswerRecord record = saveAnswerRecord(userId, question.getId(), studentAnswer, correct, score, feedback);
        LearningRecord learningRecord = upsertLearningRecord(userId, knowledgePoint.getId(), score, 2);

        return new AnswerResultVO(record.getId(), question.getId(), correct, score, question.getAnswer(),
                question.getAnalysis(), feedback, learningRecord.getLearningStatus(), learningRecord.getMasteryLevel());
    }

    private AnswerResultVO evaluateSubjectiveAnswer(Long userId,
                                                    Question question,
                                                    KnowledgePoint knowledgePoint,
                                                    String studentAnswer) {
        StudentProfile profile = findProfile(userId);
        List<AiMessage> messages = List.of(
                new AiMessage(ROLE_SYSTEM, aiPromptBuilder.buildSubjectiveAnswerPrompt(
                        profile, knowledgePoint, question.getContent(), question.getAnswer(), studentAnswer)),
                new AiMessage(ROLE_USER, "请批改这道简答题。")
        );

        AiChatResult result = null;
        try {
            result = deepSeekClient.chat(messages);
            int score = parseScore(result.getContent());
            boolean correct = score >= 60;
            AnswerRecord record = saveAnswerRecord(userId, question.getId(), studentAnswer, correct, score, result.getContent());
            LearningRecord learningRecord = upsertLearningRecord(userId, knowledgePoint.getId(), score, 4);
            saveAiCallLog(userId, REQUEST_TYPE_ANSWER_EVALUATE, result.getPromptTokens(),
                    result.getCompletionTokens(), "success", null);
            return new AnswerResultVO(record.getId(), question.getId(), correct, score, question.getAnswer(),
                    question.getAnalysis(), result.getContent(),
                    learningRecord.getLearningStatus(), learningRecord.getMasteryLevel());
        } catch (AiServiceException ex) {
            Integer promptTokens = result == null ? null : result.getPromptTokens();
            Integer completionTokens = result == null ? null : result.getCompletionTokens();
            saveAiCallLog(userId, REQUEST_TYPE_ANSWER_EVALUATE, promptTokens, completionTokens, "failed", ex.getMessage());
            throw ex;
        }
    }

    private Question toQuestion(Long knowledgePointId,
                                String requestedQuestionType,
                                String requestedDifficulty,
                                GeneratedQuestion generatedQuestion) {
        String generatedType = normalizeGeneratedQuestionType(generatedQuestion.getQuestionType());
        if (!requestedQuestionType.equals(generatedType)) {
            throw new AiServiceException("AI generated question type mismatch");
        }
        String generatedDifficulty = normalizeGeneratedDifficulty(generatedQuestion.getDifficulty());
        if (!requestedDifficulty.equals(generatedDifficulty)) {
            throw new AiServiceException("AI generated question difficulty mismatch");
        }
        List<String> options = normalizeOptions(generatedType, generatedQuestion.getOptions());

        Question question = new Question();
        question.setKnowledgePointId(knowledgePointId);
        question.setQuestionType(generatedType);
        question.setContent(requiredText(generatedQuestion.getContent(), "question content"));
        question.setOptions(writeOptions(options));
        question.setAnswer(requiredText(generatedQuestion.getAnswer(), "question answer"));
        question.setAnalysis(requiredText(generatedQuestion.getAnalysis(), "question analysis"));
        question.setDifficulty(generatedDifficulty);
        question.setSource(SOURCE_AI);
        return question;
    }

    private List<GeneratedQuestion> parseGeneratedQuestions(String content, int expectedCount) {
        String json = extractJson(content);
        GeneratedQuestionsPayload payload;
        try {
            payload = objectMapper.readValue(json, GeneratedQuestionsPayload.class);
        } catch (JsonProcessingException ex) {
            throw new AiServiceException("AI generated invalid question JSON");
        }
        if (payload.getQuestions() == null || payload.getQuestions().size() != expectedCount) {
            throw new AiServiceException("AI generated unexpected question count");
        }
        return payload.getQuestions();
    }

    private String extractJson(String content) {
        if (content == null) {
            throw new AiServiceException("AI generated empty question content");
        }
        // Models sometimes wrap JSON in text or code fences; only the outer JSON object is parsed.
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new AiServiceException("AI generated invalid question JSON");
        }
        return content.substring(start, end + 1);
    }

    private List<String> normalizeOptions(String questionType, List<String> options) {
        List<String> normalized = options == null ? List.of() : options.stream()
                .filter(option -> option != null && !option.trim().isEmpty())
                .map(String::trim)
                .toList();
        if (TYPE_SINGLE_CHOICE.equals(questionType) && normalized.size() != 4) {
            throw new AiServiceException("Single choice question requires four options");
        }
        if (TYPE_TRUE_FALSE.equals(questionType)) {
            List<String> sorted = normalized.stream()
                    .map(value -> value.toLowerCase(Locale.ROOT))
                    .sorted(Comparator.naturalOrder())
                    .toList();
            if (!List.of("false", "true").equals(sorted)) {
                throw new AiServiceException("True/false question requires true and false options");
            }
        }
        if (TYPE_SHORT_ANSWER.equals(questionType) && !normalized.isEmpty()) {
            throw new AiServiceException("Short answer question options must be empty");
        }
        return normalized;
    }

    private String normalizeQuestionType(String questionType) {
        if (questionType == null || questionType.trim().isEmpty()) {
            return TYPE_SINGLE_CHOICE;
        }
        String normalized = questionType.trim().toLowerCase(Locale.ROOT);
        if (!SUPPORTED_TYPES.contains(normalized)) {
            throw new BusinessException(400, "Unsupported question type");
        }
        return normalized;
    }

    private String normalizeDifficulty(String difficulty) {
        if (difficulty == null || difficulty.trim().isEmpty()) {
            return DIFFICULTY_MEDIUM;
        }
        String normalized = difficulty.trim().toLowerCase(Locale.ROOT);
        if (!SUPPORTED_DIFFICULTIES.contains(normalized)) {
            throw new BusinessException(400, "Unsupported difficulty");
        }
        return normalized;
    }

    private String normalizeGeneratedQuestionType(String questionType) {
        try {
            return normalizeQuestionType(questionType);
        } catch (BusinessException ex) {
            throw new AiServiceException("AI generated unsupported question type");
        }
    }

    private String normalizeGeneratedDifficulty(String difficulty) {
        try {
            return normalizeDifficulty(difficulty);
        } catch (BusinessException ex) {
            throw new AiServiceException("AI generated unsupported difficulty");
        }
    }

    private int normalizeCount(Integer count) {
        if (count == null) {
            return 3;
        }
        return Math.max(1, Math.min(count, 5));
    }

    private String requiredText(String value, String fieldName) {
        if (value == null || value.trim().isEmpty()) {
            throw new AiServiceException("AI generated empty " + fieldName);
        }
        return value.trim();
    }

    private Question requireQuestion(Long questionId) {
        Question question = questionMapper.selectById(questionId);
        if (question == null) {
            throw new BusinessException(404, "Question not found");
        }
        return question;
    }

    private KnowledgePoint requireKnowledgePoint(Long knowledgePointId) {
        KnowledgePoint knowledgePoint = knowledgePointMapper.selectById(knowledgePointId);
        if (knowledgePoint == null) {
            throw new BusinessException(404, "Knowledge point not found");
        }
        return knowledgePoint;
    }

    private StudentProfile findProfile(Long userId) {
        return studentProfileMapper.selectOne(new LambdaQueryWrapper<StudentProfile>()
                .eq(StudentProfile::getUserId, userId)
                .last("LIMIT 1"));
    }

    private String knowledgePointName(Long knowledgePointId) {
        KnowledgePoint knowledgePoint = knowledgePointMapper.selectById(knowledgePointId);
        return knowledgePoint == null ? null : knowledgePoint.getName();
    }

    private String writeOptions(List<String> options) {
        try {
            return objectMapper.writeValueAsString(options == null ? List.of() : options);
        } catch (JsonProcessingException ex) {
            throw new BusinessException(500, "Failed to save question options");
        }
    }

    private List<String> optionsFromJson(String optionsJson) {
        if (optionsJson == null || optionsJson.trim().isEmpty()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(optionsJson, new TypeReference<List<String>>() {
            });
        } catch (JsonProcessingException ex) {
            throw new BusinessException(500, "Question options format error");
        }
    }

    private boolean isObjectiveQuestion(String questionType) {
        return TYPE_SINGLE_CHOICE.equals(questionType) || TYPE_TRUE_FALSE.equals(questionType);
    }

    private boolean answersEqual(String questionType, String expectedAnswer, String studentAnswer) {
        if (TYPE_SINGLE_CHOICE.equals(questionType)) {
            return firstChoiceLetter(expectedAnswer).equals(firstChoiceLetter(studentAnswer));
        }
        if (TYPE_TRUE_FALSE.equals(questionType)) {
            return normalizeBooleanAnswer(expectedAnswer).equals(normalizeBooleanAnswer(studentAnswer));
        }
        return normalizeText(expectedAnswer).equals(normalizeText(studentAnswer));
    }

    private String firstChoiceLetter(String answer) {
        String normalized = normalizeText(answer).toUpperCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return "";
        }
        char firstChar = normalized.charAt(0);
        return firstChar >= 'A' && firstChar <= 'D' ? String.valueOf(firstChar) : normalized;
    }

    private String normalizeBooleanAnswer(String answer) {
        String normalized = normalizeText(answer).toLowerCase(Locale.ROOT);
        if (List.of("true", "t", "yes", "y", "1", "right", "correct").contains(normalized)) {
            return "true";
        }
        if (List.of("false", "f", "no", "n", "0", "wrong", "incorrect").contains(normalized)) {
            return "false";
        }
        return normalized;
    }

    private String normalizeText(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", "");
    }

    private String buildObjectiveFeedback(boolean correct, String answer, String analysis) {
        if (correct) {
            return "回答正确。\n解析：" + analysis;
        }
        return "回答不正确。正确答案是：" + answer + "\n解析：" + analysis;
    }

    private AnswerRecord saveAnswerRecord(Long userId,
                                          Long questionId,
                                          String studentAnswer,
                                          boolean correct,
                                          int score,
                                          String feedback) {
        AnswerRecord record = new AnswerRecord();
        record.setUserId(userId);
        record.setQuestionId(questionId);
        record.setUserAnswer(studentAnswer);
        record.setIsCorrect(correct ? 1 : 0);
        record.setScore(clampScore(score));
        record.setAiFeedback(limitErrorMessage(feedback));
        answerRecordMapper.insert(record);
        return record;
    }

    private LearningRecord upsertLearningRecord(Long userId,
                                                Long knowledgePointId,
                                                int masteryLevel,
                                                int additionalStudyMinutes) {
        int score = clampScore(masteryLevel);
        LearningRecord record = learningRecordMapper.selectOne(new LambdaQueryWrapper<LearningRecord>()
                .eq(LearningRecord::getUserId, userId)
                .eq(LearningRecord::getKnowledgePointId, knowledgePointId)
                .last("LIMIT 1"));
        if (record == null) {
            record = new LearningRecord();
            record.setUserId(userId);
            record.setKnowledgePointId(knowledgePointId);
            record.setLearningStatus(statusFromScore(score));
            record.setMasteryLevel(score);
            record.setStudyTime(Math.max(additionalStudyMinutes, 0));
            learningRecordMapper.insert(record);
            return record;
        }

        // Keep the best mastery level so one weak answer does not erase previously demonstrated mastery.
        int currentMastery = record.getMasteryLevel() == null ? 0 : record.getMasteryLevel();
        record.setMasteryLevel(Math.max(currentMastery, score));
        record.setLearningStatus(statusFromScore(record.getMasteryLevel()));
        int currentStudyTime = record.getStudyTime() == null ? 0 : record.getStudyTime();
        record.setStudyTime(currentStudyTime + Math.max(additionalStudyMinutes, 0));
        record.setUpdateTime(LocalDateTime.now());
        learningRecordMapper.updateById(record);
        return record;
    }

    private int parseScore(String feedback) {
        Integer score = findScore(CHINESE_SCORE_PATTERN, feedback);
        if (score == null) {
            score = findScore(ENGLISH_SCORE_PATTERN, feedback);
        }
        if (score == null) {
            score = findScore(POINT_SCORE_PATTERN, feedback);
        }
        return score == null ? 60 : clampScore(score);
    }

    private Integer findScore(Pattern pattern, String feedback) {
        if (feedback == null) {
            return null;
        }
        Matcher matcher = pattern.matcher(feedback);
        if (!matcher.find()) {
            return null;
        }
        return Integer.parseInt(matcher.group(1));
    }

    private int clampScore(int score) {
        return Math.max(0, Math.min(score, 100));
    }

    private String statusFromScore(int score) {
        if (score >= 85) {
            return STATUS_MASTERED;
        }
        if (score >= 60) {
            return STATUS_COMPLETED;
        }
        return STATUS_IN_PROGRESS;
    }

    private void saveAiCallLog(Long userId,
                               String requestType,
                               Integer promptTokens,
                               Integer completionTokens,
                               String status,
                               String errorMessage) {
        AiCallLog log = new AiCallLog();
        log.setUserId(userId);
        log.setProvider(PROVIDER_DEEPSEEK);
        log.setModelName(deepSeekClient.getModelName());
        log.setRequestType(requestType);
        log.setPromptTokens(promptTokens == null ? 0 : promptTokens);
        log.setCompletionTokens(completionTokens == null ? 0 : completionTokens);
        log.setStatus(status);
        log.setErrorMessage(limitErrorMessage(errorMessage));
        aiCallLogMapper.insert(log);
    }

    private String limitErrorMessage(String errorMessage) {
        if (errorMessage == null || errorMessage.length() <= 1000) {
            return errorMessage;
        }
        return errorMessage.substring(0, 1000);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class GeneratedQuestionsPayload {

        private List<GeneratedQuestion> questions;

        public List<GeneratedQuestion> getQuestions() {
            return questions;
        }

        public void setQuestions(List<GeneratedQuestion> questions) {
            this.questions = questions;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class GeneratedQuestion {

        private String questionType;
        private String content;
        private List<String> options;
        private String answer;
        private String analysis;
        private String difficulty;

        public String getQuestionType() {
            return questionType;
        }

        public void setQuestionType(String questionType) {
            this.questionType = questionType;
        }

        public String getContent() {
            return content;
        }

        public void setContent(String content) {
            this.content = content;
        }

        public List<String> getOptions() {
            return options;
        }

        public void setOptions(List<String> options) {
            this.options = options;
        }

        public String getAnswer() {
            return answer;
        }

        public void setAnswer(String answer) {
            this.answer = answer;
        }

        public String getAnalysis() {
            return analysis;
        }

        public void setAnalysis(String analysis) {
            this.analysis = analysis;
        }

        public String getDifficulty() {
            return difficulty;
        }

        public void setDifficulty(String difficulty) {
            this.difficulty = difficulty;
        }
    }
}
