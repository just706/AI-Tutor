package com.aitutor.agent;

import com.aitutor.exception.AiServiceException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class PythonAgentClientTest {
    HttpServer server;
    AgentProperties properties;
    final ObjectMapper mapper=new ObjectMapper();
    final AtomicInteger calls=new AtomicInteger();
    String mode="live", response="{\"version\":\"1\",\"status\":\"insufficient\"}";
    int status=200;
    @BeforeEach void setup() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/health",exchange->{
            byte[] body=("{\"version\":\"1\",\"mode\":\""+mode+"\"}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.createContext("/v1/runs",exchange->{
            calls.incrementAndGet();
            assertNull(exchange.getRequestHeaders().getFirst("Upgrade"),"Uvicorn requires HTTP/1.1 without h2c upgrade");
            assertEquals("Bearer test-service-token-123456789",exchange.getRequestHeaders().getFirst("Authorization"));
            assertEquals("POST",exchange.getRequestMethod()); exchange.getRequestBody().readAllBytes();
            byte[] body=response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Location","http://127.0.0.1:1/secret");
            exchange.sendResponseHeaders(status,body.length);
            try { exchange.getResponseBody().write(body); } finally { exchange.close(); }
        });
        server.start(); properties=new AgentProperties(); properties.setEnabled(true);
        properties.setBaseUrl("http://127.0.0.1:"+server.getAddress().getPort());
        properties.setServiceToken("test-service-token-123456789");
    }
    @AfterEach void close() { server.stop(0); }
    PythonAgentClient client() { return new PythonAgentClient(properties,mapper); }
    @Test void sendsOneAuthenticatedRequestAndReadsStructuredResult() {
        assertEquals("insufficient",client().run(Map.of("question","导数是什么？")).path("status").asText());
        assertEquals(1,calls.get());
    }
    @Test void demoServiceCannotAccidentallyAnswerRealTextbookQuestions() {
        mode="demo";
        assertThrows(AiServiceException.class,()->client().run(Map.of())); assertEquals(0,calls.get());
    }
    @Test void upstreamFailureIsSanitizedAndNeverRetried() {
        status=503; response="secret-key-and-document-text";
        var failure=assertThrows(AiServiceException.class,()->client().run(Map.of()));
        assertFalse(failure.getMessage().contains("secret")); assertEquals(1,calls.get());
    }
    @Test void redirectAndOversizedOrDuplicateKeyResponsesAreRejected() {
        status=307; assertThrows(AiServiceException.class,()->client().run(Map.of()));
        status=200; response="x".repeat(262145); assertThrows(AiServiceException.class,()->client().run(Map.of()));
        response="{\"status\":\"answered\",\"status\":\"insufficient\"}";
        assertThrows(AiServiceException.class,()->client().run(Map.of())); assertEquals(3,calls.get());
    }
    @Test void remotePlainHttpAndEmbeddedCredentialsAreRejectedBeforeNetwork() {
        for (String url: new String[]{"http://example.com","https://user:secret@example.com","https://example.com?key=value"}) {
            properties.setBaseUrl(url);
            assertThrows(IllegalArgumentException.class,()->client());
        }
    }
}
