package com.aitutor.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "ai.deepseek")
public class DeepSeekProperties {

    private String apiKey = "";
    private String baseUrl = "https://api.deepseek.com";
    private String modelName = "deepseek-v4-flash";
    private Integer timeoutMs = 60000;
    private Integer maxContextMessages = 10;

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    public Integer getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(Integer timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public Integer getMaxContextMessages() {
        return maxContextMessages;
    }

    public void setMaxContextMessages(Integer maxContextMessages) {
        this.maxContextMessages = maxContextMessages;
    }
}
