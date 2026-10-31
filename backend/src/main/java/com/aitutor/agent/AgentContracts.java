package com.aitutor.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.Map;

public final class AgentContracts {
    private AgentContracts() {}

    public record State(String scopeVersion, long expiresAt, Map<Long, String> revisions,
                        List<String> grantedIds, int searches, int reads, int reservedCalls,
                        List<String> queries, JsonNode audit) {}

    // 兼容既有 rag_context；缺少 engine 的旧记录仍使用原 Java 路径。
    public record Context(List<Long> documentIds, String question, String clarification,
                          String engine, List<String> recentQuestions, State agent) {
        public Context withAgent(State value) {
            return new Context(documentIds, question, clarification, engine, recentQuestions, value);
        }
        public boolean usesAgent() { return "python-v1".equals(engine); }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ToolRequest(@NotBlank @Size(max=100) String requestId,
            @Min(1) @Max(100) int attempt, @NotNull @Positive Long userId,
            @NotNull @Positive Long conversationId,
            @NotEmpty @Size(max=20) List<@NotNull @Positive Long> documentIds,
            @NotBlank @Size(max=100) String scopeVersion,
            @Size(min=1,max=2000) String query,
            @Size(min=1,max=3) List<@NotBlank @Size(max=100) String> anchorIds,
            Integer radius, @Min(1) @Max(8) int limit) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Chunk(String id, Long documentId, int chunkIndex, String revision, String text) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ToolResult(String requestId, int attempt, String scopeVersion, List<Chunk> chunks) {}
}
