package com.aitutor.ai;

import com.aitutor.exception.AiServiceException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(OutputCaptureExtension.class)
class DeepSeekClientTest {
    private HttpServer server;
    private final AtomicReference<String> requestBody = new AtomicReference<>();

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void upstreamErrorBodyNeverReachesTheCallerOrLogs(CapturedOutput output) throws Exception {
        DeepSeekClient client = clientResponding(400,
                "{\"error\":\"verification-sensitive-value: original user text and credentials\"}");

        AiServiceException failure = assertThrows(AiServiceException.class,
                () -> client.chat(List.of(new AiMessage("user", "测试问题"))));

        assertAll(
                () -> assertFalse(failure.getMessage().contains("verification-sensitive-value")),
                () -> assertFalse(output.getAll().contains("verification-sensitive-value")),
                () -> assertTrue(failure.getMessage().contains("400"), "保留状态码帮助排错"));
    }

    @Test
    void connectionFailureDoesNotExposeTheConfiguredUrl(CapturedOutput output) throws Exception {
        DeepSeekClient client = clientResponding(200, "{}");
        server.stop(0);

        AiServiceException failure = assertThrows(AiServiceException.class,
                () -> client.chat(List.of(new AiMessage("user", "测试问题"))));

        assertFalse(failure.getMessage().contains("http://"));
        assertFalse(output.getAll().contains("http://"));
    }

    @Test
    void jsonRequestsKeepTheStructuredResponseContract() throws Exception {
        DeepSeekClient client = clientResponding(200,
                "{\"choices\":[{\"message\":{\"content\":\"{\\\"nodes\\\":[]}\"}}],\"usage\":{\"prompt_tokens\":7,\"completion_tokens\":3}}");

        AiChatResult answer = client.chatJson(List.of(new AiMessage("user", "返回 json")));
        JsonNode sent = new ObjectMapper().readTree(requestBody.get());

        assertEquals("{\"nodes\":[]}", answer.getContent());
        assertEquals(7, answer.getPromptTokens());
        assertEquals("json_object", sent.path("response_format").path("type").asText());
        assertEquals(4096, sent.path("max_tokens").asInt());
    }

    @Test
    void ordinaryChatDoesNotRequestJsonFormatting() throws Exception {
        DeepSeekClient client = clientResponding(200,
                "{\"choices\":[{\"message\":{\"content\":\"normal answer\"}}]}");
        assertEquals("normal answer", client.chat(List.of(new AiMessage("user", "hello"))).getContent());
        JsonNode sent = new ObjectMapper().readTree(requestBody.get());
        assertFalse(sent.has("response_format"));
        assertEquals(2048, sent.path("max_tokens").asInt());
    }

    private DeepSeekClient clientResponding(int status, String body) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, response.length);
            try (var stream = exchange.getResponseBody()) { stream.write(response); }
        });
        server.start();
        DeepSeekProperties properties = new DeepSeekProperties();
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setApiKey("local-test-key");
        properties.setTimeoutMs(1000);
        return new DeepSeekClient(properties);
    }
}
