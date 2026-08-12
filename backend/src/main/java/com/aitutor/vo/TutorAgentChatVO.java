package com.aitutor.vo;

import java.util.ArrayList;
import java.util.List;

public class TutorAgentChatVO {

    private String answer;
    private String intent;
    private LearningSessionVO learningSession;
    private String teachingStrategy;
    private List<String> strategySource = new ArrayList<>();
    private KnowledgeMapContextVO knowledgeMap;
    private List<String> toolTraces = new ArrayList<>();
    private List<RagSourceVO> sources = new ArrayList<>();
    private List<String> memoryUpdates = new ArrayList<>();
    private List<OrchestratorActionVO> actions = new ArrayList<>();

    public String getAnswer() {
        return answer;
    }

    public void setAnswer(String answer) {
        this.answer = answer;
    }

    public String getIntent() {
        return intent;
    }

    public void setIntent(String intent) {
        this.intent = intent;
    }

    public LearningSessionVO getLearningSession() {
        return learningSession;
    }

    public void setLearningSession(LearningSessionVO learningSession) {
        this.learningSession = learningSession;
    }

    public String getTeachingStrategy() {
        return teachingStrategy;
    }

    public void setTeachingStrategy(String teachingStrategy) {
        this.teachingStrategy = teachingStrategy;
    }

    public List<String> getStrategySource() {
        return strategySource;
    }

    public void setStrategySource(List<String> strategySource) {
        this.strategySource = strategySource == null ? new ArrayList<>() : strategySource;
    }

    public KnowledgeMapContextVO getKnowledgeMap() {
        return knowledgeMap;
    }

    public void setKnowledgeMap(KnowledgeMapContextVO knowledgeMap) {
        this.knowledgeMap = knowledgeMap;
    }

    public List<String> getToolTraces() {
        return toolTraces;
    }

    public void setToolTraces(List<String> toolTraces) {
        this.toolTraces = toolTraces == null ? new ArrayList<>() : toolTraces;
    }

    public List<RagSourceVO> getSources() {
        return sources;
    }

    public void setSources(List<RagSourceVO> sources) {
        this.sources = sources == null ? new ArrayList<>() : sources;
    }

    public List<String> getMemoryUpdates() {
        return memoryUpdates;
    }

    public void setMemoryUpdates(List<String> memoryUpdates) {
        this.memoryUpdates = memoryUpdates == null ? new ArrayList<>() : memoryUpdates;
    }

    public List<OrchestratorActionVO> getActions() {
        return actions;
    }

    public void setActions(List<OrchestratorActionVO> actions) {
        this.actions = actions == null ? new ArrayList<>() : actions;
    }
}
