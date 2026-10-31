import com.aitutor.ai.AiPromptBuilder;
import com.aitutor.ai.TutorTurnContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.nio.file.Path;
import java.util.List;

// Export compiled production prompts without sending rubric or expected answers to the model.
class TutorFeedbackPrompts {
    public static void main(String[] args) throws Exception {
        var json = new ObjectMapper();
        var suite = json.readTree(Path.of(args[0]).toFile());
        var prompts = new AiPromptBuilder();
        ArrayNode requests = json.createArrayNode();
        for (var item : suite.path("cases")) {
            TutorTurnContext context = item.path("strategy").isNull() ? null : new TutorTurnContext(
                    "chat", item.path("topic").asText(), item.path("strategy").asText(),
                    "继续核对当前作答", List.of(), null);
            var request = requests.addObject();
            request.put("caseId", item.path("id").asText());
            var messages = request.putArray("messages");
            messages.addObject().put("role", "system").put("content", prompts.buildTutorPrompt(null, context));
            if (context != null) messages.addObject().put("role", "system")
                    .put("content", prompts.buildTutorTurnContext(context));
            for (var message : item.path("history")) messages.add(message.deepCopy());
        }
        json.writerWithDefaultPrettyPrinter().writeValue(Path.of(args[1]).toFile(), requests);
    }
}
