package com.aitutor.vo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class AiPracticeVO {

    private String topic;
    private String source;
    private String questionType;
    private String difficulty;
    private List<AiPracticeQuestionVO> questions = new ArrayList<>();

    public String getTopic() {
        return topic;
    }

    public void setTopic(String topic) {
        this.topic = topic;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getQuestionType() {
        return questionType;
    }

    public void setQuestionType(String questionType) {
        this.questionType = questionType;
    }

    public String getDifficulty() {
        return difficulty;
    }

    public void setDifficulty(String difficulty) {
        this.difficulty = difficulty;
    }

    public List<AiPracticeQuestionVO> getQuestions() {
        return questions;
    }

    public void setQuestions(List<AiPracticeQuestionVO> questions) {
        this.questions = questions;
    }
}
