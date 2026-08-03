package com.aitutor.vo;

public class AnswerResultVO {

    private Long answerRecordId;
    private Long questionId;
    private Boolean correct;
    private Integer score;
    private String correctAnswer;
    private String analysis;
    private String feedback;
    private String learningStatus;
    private Integer masteryLevel;

    public AnswerResultVO(Long answerRecordId,
                          Long questionId,
                          Boolean correct,
                          Integer score,
                          String correctAnswer,
                          String analysis,
                          String feedback,
                          String learningStatus,
                          Integer masteryLevel) {
        this.answerRecordId = answerRecordId;
        this.questionId = questionId;
        this.correct = correct;
        this.score = score;
        this.correctAnswer = correctAnswer;
        this.analysis = analysis;
        this.feedback = feedback;
        this.learningStatus = learningStatus;
        this.masteryLevel = masteryLevel;
    }

    public Long getAnswerRecordId() {
        return answerRecordId;
    }

    public void setAnswerRecordId(Long answerRecordId) {
        this.answerRecordId = answerRecordId;
    }

    public Long getQuestionId() {
        return questionId;
    }

    public void setQuestionId(Long questionId) {
        this.questionId = questionId;
    }

    public Boolean getCorrect() {
        return correct;
    }

    public void setCorrect(Boolean correct) {
        this.correct = correct;
    }

    public Integer getScore() {
        return score;
    }

    public void setScore(Integer score) {
        this.score = score;
    }

    public String getCorrectAnswer() {
        return correctAnswer;
    }

    public void setCorrectAnswer(String correctAnswer) {
        this.correctAnswer = correctAnswer;
    }

    public String getAnalysis() {
        return analysis;
    }

    public void setAnalysis(String analysis) {
        this.analysis = analysis;
    }

    public String getFeedback() {
        return feedback;
    }

    public void setFeedback(String feedback) {
        this.feedback = feedback;
    }

    public String getLearningStatus() {
        return learningStatus;
    }

    public void setLearningStatus(String learningStatus) {
        this.learningStatus = learningStatus;
    }

    public Integer getMasteryLevel() {
        return masteryLevel;
    }

    public void setMasteryLevel(Integer masteryLevel) {
        this.masteryLevel = masteryLevel;
    }
}
