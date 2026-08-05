package com.aitutor.vo;

import java.util.Map;

public class OrchestratorActionVO {

    private String actionType;
    private String title;
    private String description;
    private String label;
    private String routeName;
    private String impactLevel;
    private Map<String, Object> payload;

    public OrchestratorActionVO() {
    }

    public OrchestratorActionVO(String actionType,
                                String title,
                                String description,
                                String label,
                                String routeName,
                                String impactLevel,
                                Map<String, Object> payload) {
        this.actionType = actionType;
        this.title = title;
        this.description = description;
        this.label = label;
        this.routeName = routeName;
        this.impactLevel = impactLevel;
        this.payload = payload;
    }

    public String getActionType() {
        return actionType;
    }

    public void setActionType(String actionType) {
        this.actionType = actionType;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public String getRouteName() {
        return routeName;
    }

    public void setRouteName(String routeName) {
        this.routeName = routeName;
    }

    public String getImpactLevel() {
        return impactLevel;
    }

    public void setImpactLevel(String impactLevel) {
        this.impactLevel = impactLevel;
    }

    public Map<String, Object> getPayload() {
        return payload;
    }

    public void setPayload(Map<String, Object> payload) {
        this.payload = payload;
    }
}
