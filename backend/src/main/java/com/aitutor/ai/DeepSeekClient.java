package com.aitutor.ai;

import com.aitutor.exception.AiServiceException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Map;

@Component
public class DeepSeekClient {

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
        if (properties.getApiKey() == null || properties.getApiKey().trim().isEmpty()) {
            throw new AiServiceException("DeepSeek API key is not configured");
        }

        DeepSeekChatRequest request = new DeepSeekChatRequest(
                properties.getModelName(),
                messages,
                false,
                // The MVP expects a concise answer body; disable reasoning output for stable parsing.
                Map.of("type", "disabled")
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
        } catch (RestClientException ex) {
            throw new AiServiceException("AI service call failed");
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

    private record DeepSeekChatRequest(String model,
                                       List<AiMessage> messages,
                                       boolean stream,
                                       Map<String, String> thinking) {
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
