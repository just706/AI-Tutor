package com.aitutor.vo;

import java.util.ArrayList;
import java.util.List;

public class OrchestratorChatVO {

    private String answer;
    private String intent;
    private KnowledgePointVO matchedKnowledgePoint;
    private List<OrchestratorActionVO> actions = new ArrayList<>();

    public OrchestratorChatVO() {
    }

    public OrchestratorChatVO(String answer,
                              String intent,
                              KnowledgePointVO matchedKnowledgePoint,
                              List<OrchestratorActionVO> actions) {
        this.answer = answer;
        this.intent = intent;
        this.matchedKnowledgePoint = matchedKnowledgePoint;
        this.actions = actions == null ? new ArrayList<>() : actions;
    }

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

    public KnowledgePointVO getMatchedKnowledgePoint() {
        return matchedKnowledgePoint;
    }

    public void setMatchedKnowledgePoint(KnowledgePointVO matchedKnowledgePoint) {
        this.matchedKnowledgePoint = matchedKnowledgePoint;
    }

    public List<OrchestratorActionVO> getActions() {
        return actions;
    }

    public void setActions(List<OrchestratorActionVO> actions) {
        this.actions = actions;
    }
}
