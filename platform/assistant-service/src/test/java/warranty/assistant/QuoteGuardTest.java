package warranty.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import warranty.assistant.draft.Model.Output;
import warranty.assistant.draft.QuoteGuard;
import warranty.assistant.policy.ClauseIndex.Scored;
import warranty.assistant.policy.PolicyManual;

class QuoteGuardTest {
    private final PolicyManual manual = new PolicyManual();
    private final QuoteGuard guard = new QuoteGuard(manual);
    private final List<Scored> shown = List.of(new Scored(manual.get("5.9"), 1), new Scored(manual.get("5.2"), 1));
    private static final String RODENTS = "Wiring damage caused by rodents or other animals is not covered.";

    private String problem(String decision, String clause, String quote) {
        return guard.problem(new Output(decision, clause, quote, "because"), shown);
    }

    @Test
    void anExactSentencePasses() {
        assertThat(problem("DENY", "5.9", RODENTS)).isNull();
    }

    @Test
    void twoSentencesInARowPass() {
        assertThat(problem("DENY", "5.9", RODENTS + " The photographs of the harness must be kept with the claim.")).isNull();
    }

    @Test
    void oneChangedCharacterIsHeldBack() {
        assertThat(problem("DENY", "5.9", RODENTS.replace("not covered", "now covered"))).isEqualTo("QUOTE_MISMATCH");
        assertThat(problem("DENY", "5.9", RODENTS.toLowerCase())).isEqualTo("QUOTE_MISMATCH");
        assertThat(problem("DENY", "5.9", RODENTS + " ")).isEqualTo("QUOTE_MISMATCH");
    }

    @Test
    void halfASentenceIsHeldBackEvenThoughItMatches() {
        assertThat(manual.get("5.9").text()).contains("rodents or other animals is");
        assertThat(problem("APPROVE", "5.9", "Wiring damage caused by rodents or other animals is")).isEqualTo("QUOTE_MISMATCH");
    }

    @Test
    void aSentenceFromAnotherClauseIsHeldBack() {
        assertThat(problem("DENY", "5.2", RODENTS)).isEqualTo("QUOTE_MISMATCH");
    }

    @Test
    void aClauseTheModelWasNotShownIsHeldBack() {
        assertThat(problem("DENY", "6.3", manual.get("6.3").text())).isEqualTo("CLAUSE_NOT_SUPPLIED");
    }

    @Test
    void inventedClausesAndDecisionsAreHeldBack() {
        assertThat(problem("DENY", "9.9", RODENTS)).isEqualTo("UNKNOWN_CLAUSE");
        assertThat(problem("PAY NOW", "5.9", RODENTS)).isEqualTo("BAD_DECISION");
        assertThat(problem("DENY", "5.9", null)).isEqualTo("QUOTE_MISMATCH");
        assertThat(problem("DENY", "5.9", "")).isEqualTo("QUOTE_MISMATCH");
        assertThat(problem("NONE", null, null)).isEqualTo("NO_CLAUSE");
        assertThat(problem(null, "5.9", RODENTS)).isEqualTo("NO_CLAUSE");
    }
}
