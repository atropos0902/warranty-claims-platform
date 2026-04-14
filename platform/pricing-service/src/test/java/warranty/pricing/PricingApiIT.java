package warranty.pricing;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import warranty.commons.LocalTokenSource;
import warranty.commons.TokenSource;

@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
    "warranty.auth.local-secret=a-local-secret-that-is-long-enough-1", "warranty.auth.scopes=pricing/quote pricing/compare"})
class PricingApiIT {

    @Container
    @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.6").withDatabaseName("pricing")
            .withCommand("--innodb-buffer-pool-size=64M", "--performance-schema=OFF");

    @Autowired TestRestTemplate http;
    @Autowired TokenSource tokens;
    @Autowired JdbcClient db;

    @BeforeEach
    void clean() {
        db.sql("DELETE FROM comparison").update();
    }

    @Test
    void sixHoursOnAFourAndAQuarterHourCapPaysTheCap() {
        var q = post("/api/v1/pricing/quotes", tokens.token(), claim("6")).getBody();
        assertThat(q.get("laborPay").decimalValue()).isEqualByComparingTo("501.50"); // 4.25 x 118.00
        assertThat(q.get("steps")).isNotEmpty();
    }

    @Test
    void referenceDataMatchesWhatTheParityTestsUse() {
        for (var row : ClaimFile.claims().subList(0, 200)) {
            var expected = ClaimFile.golden().get(row.claimNo());
            Map<String, Object> body = new HashMap<>();
            var in = row.input();
            body.put("dealerCode", in.dealerCode());
            body.put("modelCode", in.modelCode());
            body.put("claimType", in.claimType());
            body.put("purchaseDate", in.purchaseDate().toString());
            body.put("failureDate", in.failureDate().toString());
            body.put("meterHours", in.meterHours());
            body.put("repairCode", in.repairCode());
            body.put("laborHours", in.laborHours());
            body.put("travelMiles", in.travelMiles());
            body.put("lines", in.lines());
            var q = post("/api/v1/pricing/quotes", tokens.token(), body);
            assertThat(q.getStatusCode().value()).isEqualTo(200);
            assertThat(q.getBody().get("totalPay").decimalValue()).as(row.claimNo()).isEqualByComparingTo(expected.total());
        }
    }

    @Test
    void unknownTermsAreReported() {
        var body = claim("6");
        body.put("modelCode", "NOPE");
        assertThat(post("/api/v1/pricing/quotes", tokens.token(), body).getStatusCode().value()).isEqualTo(422);
    }

    @Test
    void aDifferenceBlocksTheGroupUntilFinanceExplainsIt() {
        compare("C1", 1, "501.50", "501.50");
        assertThat(ready()).isTrue();
        var diff = compare("C2", 1, "531.00", "531.00");
        assertThat(diff.get("match").asBoolean()).isFalse();
        assertThat(diff.get("splitBy").asText()).isEqualTo("labor,total");
        assertThat(ready()).isFalse();

        var explain = Map.of("version", 1, "reason", "Signed in ADR 0001");
        assertThat(post("/api/v1/pricing/differences/C2/explanation", person("OPS"), explain).getStatusCode().value()).isEqualTo(403);
        assertThat(post("/api/v1/pricing/differences/C2/explanation", person("FINANCE"), explain).getStatusCode().value()).isEqualTo(200);
        assertThat(ready()).isTrue();
    }

    @Test
    void aNewVersionClearsTheExplanationAndAnOldOneChangesNothing() {
        compare("C3", 2, "531.00", "531.00");
        post("/api/v1/pricing/differences/C3/explanation", person("FINANCE"), Map.of("version", 2, "reason", "agreed"));
        assertThat(ready()).isTrue();
        compare("C3", 1, "999.00", "999.00");
        assertThat(ready()).isTrue();
        compare("C3", 3, "531.00", "531.00");
        assertThat(ready()).isFalse();
    }

    @Test
    void aGroupWithNothingComparedIsNotReady() {
        assertThat(ready()).isFalse();
    }

    @Test
    void peopleCannotPostComparisons() {
        assertThat(post("/api/v1/pricing/comparisons", person("ADJUSTER"), Map.of()).getStatusCode().value()).isIn(400, 403);
        assertThat(http.getForEntity("/api/v1/pricing/differences", String.class).getStatusCode().value()).isEqualTo(401);
    }

    private JsonNode compare(String no, int version, String labor, String total) {
        return post("/api/v1/pricing/comparisons", tokens.token(), Map.of("claimNo", no, "groupCode", "NORTH", "version", version,
                "claim", claim("6"), "legacy", Map.of("coveragePct", new BigDecimal("100"), "laborPay", new BigDecimal(labor),
                        "partsPay", 0, "travelPay", 0, "totalPay", new BigDecimal(total)))).getBody();
    }

    private boolean ready() {
        return exchange(HttpMethod.GET, "/api/v1/pricing/dealer-groups/NORTH/readiness", person("OPS"), null).getBody().get("ready").asBoolean();
    }

    private static Map<String, Object> claim(String hours) {
        Map<String, Object> m = new HashMap<>(Map.of("dealerCode", "D101", "modelCode", "T40", "claimType", "S",
                "purchaseDate", "2017-01-01", "failureDate", "2017-06-01", "meterHours", 100, "repairCode", "HYD-PUMP",
                "laborHours", new BigDecimal(hours)));
        m.put("lines", List.of());
        return m;
    }

    private String person(String role) {
        return ((LocalTokenSource) tokens).personToken("someone", List.of(role));
    }

    private ResponseEntity<JsonNode> post(String path, String token, Object body) {
        return exchange(HttpMethod.POST, path, token, body);
    }

    private ResponseEntity<JsonNode> exchange(HttpMethod m, String path, String token, Object body) {
        var h = new HttpHeaders();
        h.setBearerAuth(token);
        return http.exchange(path, m, new HttpEntity<>(body, h), JsonNode.class);
    }

    /** The web client is generated from this file. Run with OPENAPI_WRITE=true after changing an endpoint. */
    @Test
    void theCheckedInContractIsCurrent() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper()
                .configure(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
        var tree = mapper.readValue(http.getForObject("/v3/api-docs", String.class), java.util.TreeMap.class);
        tree.remove("servers");
        String now = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(tree) + "\n";
        var file = java.nio.file.Path.of("../../web/api/pricing.json");
        if ("true".equals(System.getenv("OPENAPI_WRITE"))) {
            java.nio.file.Files.writeString(file, now);
        }
        assertThat(java.nio.file.Files.readString(file)).isEqualTo(now);
    }
}
