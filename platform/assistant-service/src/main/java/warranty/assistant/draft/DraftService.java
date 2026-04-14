package warranty.assistant.draft;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import warranty.assistant.draft.Model.Claim;
import warranty.assistant.draft.Model.Output;
import warranty.assistant.policy.ClauseIndex;
import warranty.assistant.policy.ClauseIndex.Scored;
import warranty.assistant.policy.KeywordClauseIndex;
import warranty.assistant.policy.PolicyManual;
import warranty.assistant.policy.Reranker;
import warranty.commons.ApiException;

@Service
public class DraftService {
    private static final Logger log = LoggerFactory.getLogger(DraftService.class);
    private static final int SEARCHED = 12;
    private static final int SHOWN = 6;
    private final ClauseIndex index;
    private final ClauseIndex fallback;
    private final Reranker reranker;
    private final Model model;
    private final ClaimReader reader;
    private final PolicyManual manual;
    private final QuoteGuard guard;
    private final JdbcClient db;
    private final ObjectMapper json;

    public DraftService(ClauseIndex index, Reranker reranker, Model model, ClaimReader reader, PolicyManual manual,
                        JdbcClient db, ObjectMapper json) {
        this.index = index;
        this.fallback = new KeywordClauseIndex(manual);
        this.reranker = reranker;
        this.model = model;
        this.reader = reader;
        this.manual = manual;
        this.guard = new QuoteGuard(manual);
        this.db = db;
        this.json = json;
    }

    public record Draft(String claimNo, int claimVersion, String status, String decision, String clauseId, String clauseTitle,
                        String quote, String reason, String withheldWhy, String model, List<String> toolCalls) {}

    public Draft draft(String claimNo, String who) {
        var c = reader.claim(claimNo);
        if (c == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "No such claim");
        }
        Draft d = run(new Claim(c.claimNo(), c.groupCode(), c.modelCode(), c.serialNo(), c.claimType(), c.repairCode(),
                c.laborHours(), c.meterHours(), c.lines().stream().map(ClaimReader.Part::partNo).toList(), c.notes()), c.version(), who);
        return d;
    }

    public Draft run(Claim claim, int version, String who) {
        long start = System.nanoTime();
        List<Scored> clauses = retrieve(query(claim));
        var tools = new ClaimTools(reader, claim.groupCode());
        Output out;
        String problem;
        try {
            // With nothing to quote there is nothing to ask.
            out = clauses.isEmpty() ? new Output("NONE", null, null, null) : model.draft(claim, clauses, tools);
            problem = guard.problem(out, clauses);
        } catch (RuntimeException e) {
            log.warn("Model {} failed: {}", model.name(), e.toString());
            out = new Output("NONE", null, null, null);
            problem = "MODEL_FAILED";
        }
        boolean ok = problem == null;
        var clause = ok ? manual.get(out.clauseId()) : null;
        // A withheld draft carries nothing the model wrote.
        var draft = new Draft(claim.claimNo(), version, ok ? "DRAFTED" : "WITHHELD", ok ? out.decision() : null,
                ok ? clause.id() : null, ok ? clause.title() : null, ok ? out.quote() : null, ok ? out.reason() : null,
                problem, model.name(), tools.calls());
        save(draft, clauses, (int) ((System.nanoTime() - start) / 1_000_000), who);
        return draft;
    }

    public List<Scored> retrieve(String query) {
        List<Scored> found;
        try {
            found = index.search(query, SEARCHED);
        } catch (RuntimeException e) {
            log.warn("Search unavailable, using keywords: {}", e.toString());
            found = fallback.search(query, SEARCHED);
        }
        return reranker.rerank(query, found).stream().limit(SHOWN).toList();
    }

    private static String query(Claim claim) {
        return claim.notes() == null || claim.notes().isBlank() ? claim.repairCode() : claim.notes();
    }

    private void save(Draft d, List<Scored> clauses, int ms, String who) {
        db.sql("""
                INSERT INTO draft (claim_no, claim_version, model, status, decision, clause_id, quote, withheld_why,
                                   retrieved, tool_calls, latency_ms, requested_by)
                VALUES (:c, :v, :m, :s, :d, :cl, :q, :w, :r, :t, :ms, :who)""")
                .param("c", d.claimNo()).param("v", d.claimVersion()).param("m", d.model()).param("s", d.status())
                .param("d", d.decision()).param("cl", d.clauseId()).param("q", d.quote()).param("w", d.withheldWhy())
                .param("r", write(clauses.stream().map(s -> s.clause().id()).toList())).param("t", write(d.toolCalls()))
                .param("ms", ms).param("who", who).update();
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
