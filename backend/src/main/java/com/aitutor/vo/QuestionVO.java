package com.aitutor.vo;

import com.aitutor.entity.Question;

import java.time.LocalDateTime;
import java.util.List;

public class QuestionVO {

    private Long id;
    private Long knowledgePointId;
    private String knowledgePointName;
    private String questionType;
    private String content;
    private List<String> options;
    private String answer;
    private String analysis;
    private String difficulty;
    private String source;
    private LocalDateTime createTime;

    public static QuestionVO from(Question question, String knowledgePointName, List<String> options) {
        QuestionVO vo = new QuestionVO();
        vo.setId(question.getId());
        vo.setKnowledgePointId(question.getKnowledgePointId());
        vo.setKnowledgePointName(knowledgePointName);
        vo.setQuestionType(question.getQuestionType());
        vo.setContent(question.getContent());
        vo.setOptions(options);
        vo.setAnswer(question.getAnswer());
        vo.setAnalysis(question.getAnalysis());
        vo.setDifficulty(question.getDifficulty());
        vo.setSource(question.getSource());
        vo.setCreateTime(question.getCreateTime());
        return vo;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getKnowledgePointId() {
        return knowledgePointId;
    }

    public void setKnowledgePointId(Long knowledgePointId) {
        this.knowledgePointId = knowledgePointId;
    }

    public String getKnowledgePointName() {
        return knowledgePointName;
    }

    public void setKnowledgePointName(String knowledgePointName) {
        this.knowledgePointName = knowledgePointName;
    }

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

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }
}
