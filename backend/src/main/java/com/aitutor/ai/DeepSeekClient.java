package com.aitutor.ai;

import com.aitutor.exception.AiServiceException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;
import java.util.Map;

@Component
public class DeepSeekClient {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekClient.class);

    private final DeepSeekProperties properties;
    private final RestClient restClient;

    public DeepSeekClient(DeepSeekProperties properties) {
        this.properties = properties;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        int timeoutMs = properties.getTimeoutMs() == null ? 60000 : properties.getTimeoutMs();
        requestFactory.setConnectTimeout(timeoutMs);
        requestFactory.setReadTimeout(timeoutMs);
        this.restClient = RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .requestFactory(requestFactory)
                .build();
    }

    public AiChatResult chat(List<AiMessage> messages) {
        return chat(messages, false);
    }

    public AiChatResult chatJson(List<AiMessage> messages) {
        return chat(messages, true);
    }

    private AiChatResult chat(List<AiMessage> messages, boolean jsonOutput) {
        if (properties.getApiKey() == null || properties.getApiKey().trim().isEmpty()) {
            throw new AiServiceException("DeepSeek API key is not configured");
        }

        Map<String, String> responseFormat = jsonOutput ? Map.of("type", "json_object") : null;
        DeepSeekChatRequest request = new DeepSeekChatRequest(
                properties.getModelName(),
                messages,
                false,
                // The MVP expects a concise answer body; disable reasoning output for stable parsing.
                Map.of("type", "disabled"),
                responseFormat,
                jsonOutput ? 4096 : 2048
        );
        try {
            DeepSeekChatResponse response = restClient.post()
                    .uri("/chat/completions")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getApiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(DeepSeekChatResponse.class);

            return parseResponse(response);
        } catch (AiServiceException ex) {
            throw ex;
        } catch (RestClientResponseException ex) {
            // Upstream error bodies can contain credentials or submitted material.
            int status = ex.getStatusCode().value();
            log.warn("DeepSeek request failed with HTTP status {}", status);
            throw new AiServiceException("AI service call failed (HTTP " + status + ")");
        } catch (RestClientException ex) {
            log.warn("DeepSeek request failed ({})", ex.getClass().getSimpleName());
            throw new AiServiceException("AI service is temporarily unavailable; please retry later");
        }
    }

    public String getModelName() {
        return properties.getModelName();
    }

    private AiChatResult parseResponse(DeepSeekChatResponse response) {
        if (response == null || response.getChoices() == null || response.getChoices().isEmpty()) {
            throw new AiServiceException("AI service returned an empty response");
        }

        DeepSeekChoice choice = response.getChoices().get(0);
        if (choice.getMessage() == null || choice.getMessage().getContent() == null
                || choice.getMessage().getContent().trim().isEmpty()) {
            throw new AiServiceException("AI service returned an empty response");
        }

        Integer promptTokens = null;
        Integer completionTokens = null;
        if (response.getUsage() != null) {
            promptTokens = response.getUsage().getPromptTokens();
            completionTokens = response.getUsage().getCompletionTokens();
        }

        return new AiChatResult(choice.getMessage().getContent(), promptTokens, completionTokens);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record DeepSeekChatRequest(String model,
                                       List<AiMessage> messages,
                                       boolean stream,
                                       Map<String, String> thinking,
                                       @JsonProperty("response_format") Map<String, String> responseFormat,
                                       @JsonProperty("max_tokens") Integer maxTokens) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class DeepSeekChatResponse {

        private List<DeepSeekChoice> choices;
        private DeepSeekUsage usage;

        public List<DeepSeekChoice> getChoices() {
            return choices;
        }

        public void setChoices(List<DeepSeekChoice> choices) {
            this.choices = choices;
        }

        public DeepSeekUsage getUsage() {
            return usage;
        }

        public void setUsage(DeepSeekUsage usage) {
            this.usage = usage;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class DeepSeekChoice {

        private DeepSeekResponseMessage message;

        public DeepSeekResponseMessage getMessage() {
            return message;
        }

        public void setMessage(DeepSeekResponseMessage message) {
            this.message = message;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class DeepSeekResponseMessage {

        private String content;

        public String getContent() {
            return content;
        }

        public void setContent(String content) {
            this.content = content;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class DeepSeekUsage {

        @JsonProperty("prompt_tokens")
        private Integer promptTokens;
        @JsonProperty("completion_tokens")
        private Integer completionTokens;

        public Integer getPromptTokens() {
            return promptTokens;
        }

        public void setPromptTokens(Integer promptTokens) {
            this.promptTokens = promptTokens;
        }

        public Integer getCompletionTokens() {
            return completionTokens;
        }

        public void setCompletionTokens(Integer completionTokens) {
            this.completionTokens = completionTokens;
        }
    }
}
