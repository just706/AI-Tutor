package com.aitutor.agent;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.agent")
public class AgentProperties {
    private boolean enabled;
    private String baseUrl = "http://127.0.0.1:8091";
    private String serviceToken = "";
    private String callbackToken = "";
    private int timeoutMs = 55000;
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean value) { enabled = value; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String value) { baseUrl = value; }
    public String getServiceToken() { return serviceToken; }
    public void setServiceToken(String value) { serviceToken = value; }
    public String getCallbackToken() { return callbackToken; }
    public void setCallbackToken(String value) { callbackToken = value; }
    public int getTimeoutMs() { return timeoutMs; }
    public void setTimeoutMs(int value) { timeoutMs = value; }
}
