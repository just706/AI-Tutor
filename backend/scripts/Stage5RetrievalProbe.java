import com.aitutor.ai.RagFollowUpResolver;
import com.aitutor.config.RagProperties;
import com.aitutor.entity.DocumentChunk;
import com.aitutor.entity.LearningDocument;
import com.aitutor.mapper.DocumentChunkMapper;
import com.aitutor.service.impl.RagServiceImpl;
import com.aitutor.vo.RagSourceVO;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// Offline probe: invoke production retrieval without copying ranking logic into an evaluator.
// The mapper supplies already selected synthetic chunks. SQL, ownership, parsing and chat are NOT tested.
class Stage5RetrievalProbe {
    record Outcome(String resolvedQuestion, String clarification, List<String> retrieved) { }

    @SuppressWarnings("unchecked")
    static Outcome retrieve(JsonNode item, RagServiceImpl service, Method retrieve, Method toSources,
                            List<LearningDocument> documents, Map<Long, String> names) throws Exception {
        List<String> recent = new ArrayList<>();
        item.path("setup").forEach(question -> recent.add(question.asText()));
        Collections.reverse(recent);
        var resolution = RagFollowUpResolver.resolve(item.path("question").asText(), recent);
        if (resolution.clarification() != null) return new Outcome(resolution.question(), resolution.clarification(), List.of());
        var chunks = retrieve.invoke(service, resolution.question(), documents);
        List<RagSourceVO> sources = (List<RagSourceVO>) toSources.invoke(service, chunks);
        return new Outcome(resolution.question(), null, sources.stream()
                .map(source -> names.get(source.getDocumentId()) + ":" + source.getChunkIndex()).toList());
    }

    public static void main(String[] args) throws Exception {
        var json = new ObjectMapper();
        var input = json.readTree(Path.of(args[0]).toFile());
        Map<String, LearningDocument> documentMap = new LinkedHashMap<>();
        Map<Long, String> names = new LinkedHashMap<>();
        List<DocumentChunk> allChunks = new ArrayList<>();
        for (var value : input.path("documents")) {
            var document = new LearningDocument();
            document.setId((long) documentMap.size() + 1);
            document.setFileName(value.path("id").asText() + ".txt");
            documentMap.put(value.path("id").asText(), document);
            names.put(document.getId(), value.path("id").asText());
            int index = 0;
            for (var valueChunk : value.path("chunks")) {
                var chunk = new DocumentChunk();
                chunk.setId((long) allChunks.size() + 1);
                chunk.setDocumentId(document.getId());
                chunk.setChunkIndex(index++);
                chunk.setChunkText(valueChunk.path("text").asText());
                allChunks.add(chunk);
            }
        }
        var retrieveMethod = RagServiceImpl.class.getDeclaredMethod("retrieveChunks", String.class, List.class);
        var toSources = RagServiceImpl.class.getDeclaredMethod("toSources", List.class);
        retrieveMethod.setAccessible(true);
        toSources.setAccessible(true);
        var properties = new RagProperties();
        properties.setTopK(input.path("topK").asInt());
        var results = json.createArrayNode();
        for (var item : input.path("cases")) {
            List<LearningDocument> documents = new ArrayList<>();
            item.path("documents").forEach(id -> documents.add(documentMap.get(id.asText())));
            var ids = documents.stream().map(LearningDocument::getId).toList();
            var selectedChunks = allChunks.stream().filter(chunk -> ids.contains(chunk.getDocumentId())).toList();
            var mapper = (DocumentChunkMapper) Proxy.newProxyInstance(DocumentChunkMapper.class.getClassLoader(),
                    new Class<?>[]{DocumentChunkMapper.class}, (proxy, method, parameters) -> {
                        if (method.getName().equals("selectList")) return selectedChunks;
                        throw new UnsupportedOperationException("Unexpected mapper operation: " + method.getName());
                    });
            var service = new RagServiceImpl(null, mapper, null, null, null, null, null,
                    properties, null, null, json, null, null, null, null);
            Outcome outcome = null;
            for (int warmup = 0; warmup < 3; warmup++) outcome = retrieve(item, service, retrieveMethod, toSources, documents, names);
            double[] elapsed = new double[7];
            for (int sample = 0; sample < elapsed.length; sample++) {
                long started = System.nanoTime();
                Outcome measured = retrieve(item, service, retrieveMethod, toSources, documents, names);
                elapsed[sample] = (System.nanoTime() - started) / 1_000_000.0;
                if (!measured.equals(outcome)) throw new IllegalStateException("Non-deterministic retrieval: " + item.path("id").asText());
            }
            var row = results.addObject();
            row.put("caseId", item.path("id").asText());
            row.put("resolvedQuestion", outcome.resolvedQuestion());
            row.put("clarification", outcome.clarification());
            row.set("retrieved", json.valueToTree(outcome.retrieved()));
            row.set("timingSamplesMs", json.valueToTree(elapsed));
            Arrays.sort(elapsed);
            row.put("elapsedMs", elapsed[elapsed.length / 2]);
        }
        json.writerWithDefaultPrettyPrinter().writeValue(Path.of(args[1]).toFile(), results);
    }
}
