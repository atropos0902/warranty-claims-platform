package warranty.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import warranty.assistant.draft.ClaimReader;
import warranty.assistant.draft.ClaimTools;
import warranty.assistant.policy.KeywordClauseIndex;
import warranty.assistant.policy.PolicyManual;

class PolicyTest {
    private final PolicyManual manual = new PolicyManual();

    @Test
    void everyClauseIsReadAndNumberedOnce() {
        assertThat(manual.clauses()).hasSize(40);
        assertThat(new HashSet<>(manual.clauses().stream().map(c -> c.id()).toList())).hasSize(40);
        assertThat(manual.clauses()).allSatisfy(c -> assertThat(c.text()).endsWith("."));
        assertThat(manual.get("2.1").text()).startsWith("Labor hours are limited to the cap");
    }

    @Test
    void keywordsFindTheRightClause() {
        var index = new KeywordClauseIndex(manual);
        assertThat(index.search("mice chewed the wiring harness, rodent nest found", 3).getFirst().clause().id()).isEqualTo("5.9");
        assertThat(index.search("hose leaking at the crimped fitting", 3).getFirst().clause().id()).isEqualTo("5.2");
        assertThat(index.search("zzz qqq", 3)).isEmpty();
    }

    @Test
    void theModelGetsThreeToolsAndAllOfThemRead() {
        ToolCallback[] tools = ToolCallbacks.from(new ClaimTools(null, "NORTH"));
        assertThat(Arrays.stream(tools).map(t -> t.getToolDefinition().name()).toList())
                .containsExactlyInAnyOrder("claimLookup", "partWarrantyTerms", "priorRepairs");
    }

    @Test
    void toolsDoNotLeaveTheDealerGroupOfTheClaim() {
        var reader = new ClaimReader(null, null) {
            @Override
            public ClaimData claim(String claimNo) {
                return new ClaimData(claimNo, "WEST", null, null, null, null, null, null, null, null, null, null, 1, List.of());
            }

            @Override
            public List<Repair> repairs(String serialNo) {
                return List.of(new Repair("A", "NORTH", "HYD-PUMP", "APPROVED"), new Repair("B", "WEST", "HYD-PUMP", "DENIED"));
            }
        };
        var tools = new ClaimTools(reader, "NORTH");
        assertThat(tools.claimLookup("X1")).isEqualTo("No such claim");
        assertThat((List<?>) tools.priorRepairs("SN")).hasSize(1);
        assertThat(tools.calls()).containsExactly("claimLookup X1", "priorRepairs SN");
    }
}
