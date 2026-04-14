package warranty.assistant.policy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/** The manual, one entry for each numbered clause. Quotes are checked against this text. */
@Component
public class PolicyManual {
    private static final Pattern HEADING = Pattern.compile("^### (\\d+\\.\\d+) (.+)$");
    private final List<Clause> clauses;
    private final Map<String, Clause> byId;

    public PolicyManual() {
        this(read());
    }

    PolicyManual(String markdown) {
        List<Clause> out = new ArrayList<>();
        String id = null;
        String title = null;
        var text = new StringBuilder();
        for (String line : (markdown + "\n### 0.0 end").split("\n")) {
            Matcher m = HEADING.matcher(line);
            if (m.matches()) {
                if (id != null) {
                    out.add(new Clause(id, title, text.toString().strip()));
                }
                id = m.group(1);
                title = m.group(2);
                text.setLength(0);
            } else if (id != null) {
                text.append(line).append('\n');
            }
        }
        clauses = List.copyOf(out);
        byId = clauses.stream().collect(Collectors.toMap(Clause::id, Function.identity()));
    }

    public List<Clause> clauses() {
        return clauses;
    }

    public Clause get(String id) {
        return id == null ? null : byId.get(id);
    }

    private static String read() {
        try {
            return new ClassPathResource("policy/manual.md").getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
