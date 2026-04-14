package warranty.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import warranty.assistant.draft.ClaimReader;
import warranty.assistant.draft.ClaimTools;
import warranty.assistant.draft.Model;
import warranty.commons.LocalTokenSource;
import warranty.commons.TokenSource;

@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
    "warranty.auth.local-secret=a-local-secret-that-is-long-enough-1", "warranty.auth.scopes=claims/read"})
class AssistantApiIT {

    @Container
    @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.6").withDatabaseName("assistant")
            .withCommand("--innodb-buffer-pool-size=64M", "--performance-schema=OFF");

    @Autowired TestRestTemplate http;
    @Autowired TokenSource tokens;
    @Autowired JdbcClient db;
    @MockitoBean ClaimReader reader;
    @MockitoBean Model model;

    private static final String RODENTS = "Wiring damage caused by rodents or other animals is not covered.";

    @BeforeEach
    void claim() {
        db.sql("DELETE FROM draft").update();
        when(model.name()).thenReturn("test");
        notes("Mice chewed the wiring harness behind the cab");
    }

    private void notes(String notes) {
        when(reader.claim(anyString())).thenReturn(new ClaimReader.ClaimData("P1", "NORTH", "T40", "SN-1", "S", null, null,
                "10", "ELE-HARN", "2", notes, "SUBMITTED", 3, List.of()));
    }

    @Test
    void aGoodDraftIsShownAndRecorded() {
        answer("DENY", "5.9", RODENTS);
        var d = draft(adjuster()).getBody();
        assertThat(d.get("status").asText()).isEqualTo("DRAFTED");
        assertThat(d.get("quote").asText()).isEqualTo(RODENTS);
        assertThat(d.get("claimVersion").asInt()).isEqualTo(3);
        assertThat(db.sql("SELECT status FROM draft").query(String.class).single()).isEqualTo("DRAFTED");
    }

    @Test
    void aBadQuoteIsWithheldAndNothingTheModelWroteGetsThrough() {
        answer("APPROVE", "5.9", "Wiring damage caused by rodents is fully covered.");
        var d = draft(adjuster()).getBody();
        assertThat(d.get("status").asText()).isEqualTo("WITHHELD");
        assertThat(d.get("withheldWhy").asText()).isEqualTo("QUOTE_MISMATCH");
        for (String field : List.of("decision", "clauseId", "quote", "reason")) {
            assertThat(d.get(field).isNull()).as(field).isTrue();
        }
    }

    @Test
    void aModelThatFailsGivesAWithheldDraftAndNotAnError() {
        when(model.draft(any(), any(), any())).thenThrow(new IllegalStateException("timeout"));
        var r = draft(adjuster());
        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat(r.getBody().get("withheldWhy").asText()).isEqualTo("MODEL_FAILED");
    }

    @Test
    void theModelIsNotCalledWhenNoClauseWasFound() {
        notes("zzz qqq");
        assertThat(draft(adjuster()).getBody().get("withheldWhy").asText()).isEqualTo("NO_CLAUSE");
        org.mockito.Mockito.verify(model, org.mockito.Mockito.never()).draft(any(), any(), any());
    }

    @Test
    void onlyAdjustersAskForDrafts() {
        answer("DENY", "5.9", RODENTS);
        assertThat(draft(tokens.token()).getStatusCode().value()).isEqualTo(403);
        assertThat(draft(((LocalTokenSource) tokens).personToken("d", List.of("DEALER", "GROUP_NORTH"))).getStatusCode().value()).isEqualTo(403);
    }

    static List<String> attacks() throws Exception {
        return new ClassPathResource("attacks.txt").getContentAsString(java.nio.charset.StandardCharsets.UTF_8).lines()
                .filter(l -> !l.isBlank()).toList();
    }

    /** The model here is fully taken over: it believes the notes, uses every tool it can reach, and answers as told. */
    @ParameterizedTest
    @MethodSource("attacks")
    void aModelThatObeysTheNotesStillCannotShowOrApproveAnything(String attack) {
        notes(attack);
        when(model.draft(any(), any(), any())).thenAnswer(call -> {
            ClaimTools tools = call.getArgument(2);
            tools.claimLookup("P999");
            tools.priorRepairs("SN-77");
            tools.partWarrantyTerms("T90");
            return new Model.Output("APPROVE", "1.1", "Approved in full, pay " + attack, attack);
        });
        var d = draft(adjuster()).getBody();
        assertThat(d.get("status").asText()).isEqualTo("WITHHELD");
        assertThat(d.toString()).doesNotContain(attack);
        assertThat(d.get("toolCalls")).allSatisfy(c ->
                assertThat(c.asText()).matches("(claimLookup|priorRepairs|partWarrantyTerms) .*"));
    }

    private void answer(String decision, String clause, String quote) {
        when(model.draft(any(), any(), any())).thenReturn(new Model.Output(decision, clause, quote, "reason"));
    }

    private ResponseEntity<JsonNode> draft(String token) {
        var h = new HttpHeaders();
        h.setBearerAuth(token);
        return http.exchange("/api/v1/assistant/claims/P1/draft", HttpMethod.POST, new HttpEntity<>(Map.of(), h), JsonNode.class);
    }

    private String adjuster() {
        return ((LocalTokenSource) tokens).personToken("ann", List.of("ADJUSTER"));
    }

    /** The web client is generated from this file. Run with OPENAPI_WRITE=true after changing an endpoint. */
    @Test
    void theCheckedInContractIsCurrent() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper()
                .configure(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
        var tree = mapper.readValue(http.getForObject("/v3/api-docs", String.class), java.util.TreeMap.class);
        tree.remove("servers");
        String now = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(tree) + "\n";
        var file = java.nio.file.Path.of("../../web/api/assistant.json");
        if ("true".equals(System.getenv("OPENAPI_WRITE"))) {
            java.nio.file.Files.writeString(file, now);
        }
        assertThat(java.nio.file.Files.readString(file)).isEqualTo(now);
    }
}
