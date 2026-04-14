package warranty.assistant.draft;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.core.io.ClassPathResource;
import warranty.assistant.policy.ClauseIndex.Scored;

/** Any Spring AI chat model: Bedrock Converse, OpenAI or Ollama. */
public class ChatModelAdapter implements Model {
    private final ChatClient chat;
    private final String name;
    private final ObjectMapper json;
    private final String system = read("prompts/draft-system.txt");
    private final String user = read("prompts/draft-user.txt");

    public ChatModelAdapter(ChatModel model, ObjectMapper json) {
        this.chat = ChatClient.create(model);
        this.name = String.valueOf(model.getDefaultOptions().getModel());
        this.json = json;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public Output draft(Claim claim, List<Scored> clauses, ClaimTools tools) {
        String facts = write(new Claim(claim.claimNo(), claim.groupCode(), claim.modelCode(), claim.serialNo(),
                claim.claimType(), claim.repairCode(), claim.laborHours(), claim.meterHours(), claim.parts(), null));
        String policy = clauses.stream().map(s -> s.clause().id() + " " + s.clause().title() + "\n" + s.clause().text())
                .collect(Collectors.joining("\n\n"));
        // Markers inside the notes could close the block early.
        String notes = claim.notes() == null ? "" : claim.notes().replace("NOTES>>>", "").replace("<<<NOTES", "");
        Output out = chat.prompt().system(system)
                .user(user.replace("{claim}", facts).replace("{clauses}", policy).replace("{notes}", notes))
                .tools(tools)
                .call().entity(Output.class);
        return out == null ? new Output("NONE", null, null, "The model gave no answer.") : out;
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String read(String path) {
        try {
            return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
