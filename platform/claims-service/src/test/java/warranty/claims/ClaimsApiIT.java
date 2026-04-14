package warranty.claims;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.sqs.SqsClient;
import warranty.claims.api.ClaimRequest;
import warranty.claims.api.ClaimView;
import warranty.claims.data.GroupStore;
import warranty.claims.data.GroupStore.Owner;
import warranty.claims.flow.OutboxRelay;
import warranty.claims.flow.PricingGateway;
import warranty.claims.flow.RepriceJob;
import warranty.claims.flow.StagingLoader;
import warranty.commons.LocalTokenSource;
import warranty.commons.TokenSource;

@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
    "warranty.jobs.enabled=false", "warranty.auth.local-secret=a-local-secret-that-is-long-enough-1",
    "warranty.auth.scopes=claims/read"})
class ClaimsApiIT {

    @Container
    @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.6").withUsername("root").withPassword("root")
            .withDatabaseName("claims")
            .withCopyFileToContainer(MountableFile.forClasspathResource("init.sql"), "/docker-entrypoint-initdb.d/init.sql")
            .withCommand("--innodb-buffer-pool-size=64M", "--performance-schema=OFF");

    @Autowired TestRestTemplate http;
    @Autowired TokenSource tokens;
    @Autowired JdbcClient db;
    @Autowired GroupStore groups;
    @Autowired ObjectMapper json;
    @Autowired StagingLoader loader;
    @Autowired OutboxRelay relay;
    @Autowired RepriceJob reprice;
    @MockitoBean PricingGateway pricing;
    @MockitoBean SqsClient sqs;

    private static final ClaimView.Price PRICE = new ClaimView.Price(new BigDecimal("100.0"), new BigDecimal("501.50"),
            new BigDecimal("110.00"), new BigDecimal("21.25"), new BigDecimal("632.75"),
            List.of(new ClaimView.Step("labor", "4.25 of 6 hours paid, cap 4.25")));

    @BeforeEach
    void clean() {
        for (String t : List.of("claim_line", "claim", "idempotency_key", "outbox", "staging.claims", "staging.claim_lines", "staging.change_queue")) {
            db.sql("DELETE FROM " + t).update();
        }
        owner("NORTH", Owner.PLATFORM);
        owner("SOUTH", Owner.LEGACY);
        reset(sqs);
        doReturn(Optional.of(PRICE)).when(pricing).price(any());
    }

    // filing

    @Test
    void filingTwiceWithOneKeyGivesOneClaimAndOneEvent() {
        var first = file("key-00001", request("6"));
        var second = file("key-00001", request("6"));
        assertThat(first.getStatusCode().value()).isEqualTo(201);
        assertThat(second.getStatusCode().value()).isEqualTo(200);
        assertThat(second.getBody().get("claimNo")).isEqualTo(first.getBody().get("claimNo"));
        assertThat(count("claim")).isEqualTo(1);
        assertThat(count("outbox")).isEqualTo(1);
    }

    @Test
    void aKeyCannotBeReusedForADifferentClaim() {
        var first = file("key-00002", request("6"));
        var other = file("key-00002", request("5"));
        assertThat(other.getStatusCode().value()).isEqualTo(422);
        assertThat(other.getBody().get("code").asText()).isEqualTo("KEY_REUSED");
        assertThat(other.getBody().get("claimNo")).isEqualTo(first.getBody().get("claimNo"));
    }

    @Test
    void theSameKeySentManyTimesAtOnceStillGivesOneClaim() throws Exception {
        List<Callable<ResponseEntity<JsonNode>>> calls = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            calls.add(() -> file("key-00003", request("6")));
        }
        Set<String> numbers = new HashSet<>();
        try (var pool = Executors.newFixedThreadPool(12)) {
            for (var f : pool.invokeAll(calls)) {
                assertThat(f.get().getStatusCode().is2xxSuccessful()).isTrue();
                numbers.add(f.get().getBody().get("claimNo").asText());
            }
        }
        assertThat(numbers).hasSize(1);
        assertThat(count("claim")).isEqualTo(1);
    }

    @Test
    void aGroupOnTheOldSystemIsRefusedAndNothingIsKept() {
        var r = call(HttpMethod.POST, "/api/v1/claims", dealer("SOUTH"), request("6", "D201"), "Idempotency-Key", "key-00004");
        assertThat(r.getStatusCode().value()).isEqualTo(409);
        assertThat(r.getBody().get("code").asText()).isEqualTo("GROUP_ON_LEGACY");
        assertThat(count("idempotency_key")).isZero();
    }

    @Test
    void pricingDownKeepsTheClaimAndPricesItLater() {
        doReturn(Optional.empty()).when(pricing).price(any());
        var r = file("key-00005", request("6"));
        assertThat(r.getStatusCode().value()).isEqualTo(201);
        assertThat(r.getBody().get("price").isNull()).isTrue();
        String no = r.getBody().get("claimNo").asText();
        assertThat(decide(no, 1, "APPROVED", person("ann", "ADJUSTER")).getBody().get("code").asText()).isEqualTo("NOT_PRICED");

        doReturn(Optional.of(PRICE)).when(pricing).price(any());
        reprice.run();
        var now = call(HttpMethod.GET, "/api/v1/claims/" + no, person("ann", "ADJUSTER"), null).getBody();
        assertThat(now.get("price").get("totalPay").decimalValue()).isEqualByComparingTo("632.75");
        assertThat(now.get("version").asInt()).isEqualTo(2);
        assertThat(count("outbox")).isEqualTo(2);
    }

    @Test
    void badFieldsAreNamed() {
        var bad = new ClaimRequest("D101", "T40", "X", "S", LocalDate.parse("2017-01-01"), LocalDate.parse("2017-02-01"), null,
                "HYD-PUMP", new BigDecimal("-1"), null, null, List.of());
        var r = file("key-00006", bad);
        assertThat(r.getStatusCode().value()).isEqualTo(400);
        assertThat(r.getBody().get("fields").has("laborHours")).isTrue();
    }

    // editing at the same time

    @Test
    void aStaleEditIsRefusedWithTheCurrentClaim() {
        String no = file("key-00010", request("6")).getBody().get("claimNo").asText();
        assertThat(edit(no, 1, request("5")).getStatusCode().value()).isEqualTo(200);
        var stale = edit(no, 1, request("3"));
        assertThat(stale.getStatusCode().value()).isEqualTo(412);
        assertThat(stale.getBody().get("current").get("laborHours").decimalValue()).isEqualByComparingTo("5");
        assertThat(stale.getBody().get("current").get("version").asInt()).isEqualTo(2);
    }

    @Test
    void ofManyEditsFromOneVersionExactlyOneIsSaved() throws Exception {
        String no = file("key-00011", request("6")).getBody().get("claimNo").asText();
        List<Callable<Integer>> calls = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            String hours = String.valueOf(i + 1);
            calls.add(() -> edit(no, 1, request(hours)).getStatusCode().value());
        }
        List<Integer> codes = new ArrayList<>();
        try (var pool = Executors.newFixedThreadPool(10)) {
            for (var f : pool.invokeAll(calls)) {
                codes.add(f.get());
            }
        }
        assertThat(codes.stream().filter(c -> c == 200).count()).isEqualTo(1);
        assertThat(codes.stream().filter(c -> c == 412).count()).isEqualTo(9);
        assertThat(count("outbox")).isEqualTo(2);
    }

    @Test
    void anEditNeedsAVersion() {
        String no = file("key-00012", request("6")).getBody().get("claimNo").asText();
        assertThat(call(HttpMethod.PUT, "/api/v1/claims/" + no, dealer("NORTH"), request("5")).getStatusCode().value()).isEqualTo(428);
    }

    // who may do what

    @Test
    void aServiceTokenCannotDecideAndNeitherCanADealer() {
        String no = file("key-00020", request("6")).getBody().get("claimNo").asText();
        assertThat(decide(no, 1, "APPROVED", tokens.token()).getStatusCode().value()).isEqualTo(403);
        assertThat(decide(no, 1, "APPROVED", dealer("NORTH")).getStatusCode().value()).isEqualTo(403);
        assertThat(decide(no, 1, "APPROVED", null).getStatusCode().value()).isEqualTo(401);
        var ok = decide(no, 1, "APPROVED", person("ann", "ADJUSTER"));
        assertThat(ok.getStatusCode().value()).isEqualTo(200);
        assertThat(ok.getBody().get("status").asText()).isEqualTo("APPROVED");
        assertThat(decide(no, 2, "DENIED", person("ann", "ADJUSTER")).getBody().get("code").asText()).isEqualTo("DECIDED");
    }

    @Test
    void aServiceTokenCanReadButNotFileOrEdit() {
        String no = file("key-00021", request("6")).getBody().get("claimNo").asText();
        assertThat(call(HttpMethod.GET, "/api/v1/claims/" + no, tokens.token(), null).getStatusCode().value()).isEqualTo(200);
        assertThat(call(HttpMethod.POST, "/api/v1/claims", tokens.token(), request("6"), "Idempotency-Key", "key-00022").getStatusCode().value()).isEqualTo(403);
        assertThat(call(HttpMethod.PUT, "/api/v1/claims/" + no, tokens.token(), request("5"), "If-Match", "\"1\"").getStatusCode().value()).isEqualTo(403);
        assertThat(call(HttpMethod.POST, "/api/v1/dealer-groups/NORTH/owner", tokens.token(), java.util.Map.of("from", "PLATFORM", "to", "LEGACY")).getStatusCode().value()).isEqualTo(403);
    }

    @Test
    void dealersSeeOnlyTheirOwnGroup() {
        String no = file("key-00023", request("6")).getBody().get("claimNo").asText();
        assertThat(call(HttpMethod.GET, "/api/v1/claims/" + no, dealer("SOUTH"), null).getStatusCode().value()).isEqualTo(404);
        assertThat(call(HttpMethod.GET, "/api/v1/claims", dealer("SOUTH"), null).getBody()).isEmpty();
        assertThat(call(HttpMethod.GET, "/api/v1/claims", dealer("NORTH"), null).getBody()).hasSize(1);
        assertThat(call(HttpMethod.POST, "/api/v1/claims", dealer("SOUTH"), request("6", "D101"), "Idempotency-Key", "key-00024").getStatusCode().value()).isEqualTo(404);
    }

    // migration

    @Test
    void theLoaderCopiesLegacyRowsAndAsksForAComparison() {
        stage("L1", "D201", 1, 1, "500.00");
        line("L1", 1);
        loader.run();
        var c = call(HttpMethod.GET, "/api/v1/claims/L1", person("ann", "ADJUSTER"), null).getBody();
        assertThat(c.get("price").get("totalPay").decimalValue()).isEqualByComparingTo("500.00");
        assertThat(db.sql("SELECT destination FROM outbox").query(String.class).single()).isEqualTo("COMPARE");
        assertThat(count("staging.change_queue")).isZero();
    }

    @Test
    void theLoaderWaitsForEveryLine() {
        stage("L2", "D201", 1, 2, "500.00");
        line("L2", 1);
        loader.run();
        assertThat(count("claim")).isZero();
        line("L2", 2);
        loader.run();
        assertThat(count("claim")).isEqualTo(1);
    }

    @Test
    void theLoaderSkipsAGroupThePlatformOwns() {
        String no = file("key-00030", request("6")).getBody().get("claimNo").asText();
        stage(no, "D101", 1, 0, "1.00");
        db.sql("UPDATE staging.claims SET notes = 'stale echo' WHERE claim_no = :n").param("n", no).update();
        loader.run();
        var c = call(HttpMethod.GET, "/api/v1/claims/" + no, person("ann", "ADJUSTER"), null).getBody();
        assertThat(c.get("notes").asText()).isEqualTo("Pump whines under load");
        assertThat(count("staging.change_queue")).isZero();
    }

    @Test
    void theLoaderNeverGoesBackToAnOlderVersion() {
        stage("L3", "D201", 3, 0, "300.00");
        loader.run();
        db.sql("UPDATE staging.claims SET version_no = 2, total_pay = 200 WHERE claim_no = 'L3'").update();
        loader.run();
        assertThat(db.sql("SELECT total_pay FROM claim WHERE claim_no = 'L3'").query(BigDecimal.class).single()).isEqualByComparingTo("300");
    }

    @Test
    void theOwnerChangesOnlyFromTheExpectedValue() {
        assertThat(groups.changeOwner("SOUTH", Owner.PLATFORM, Owner.RETURNING)).isFalse();
        assertThat(groups.changeOwner("SOUTH", Owner.LEGACY, Owner.MOVING)).isTrue();
    }

    // relay

    @Test
    void aFailedSendKeepsTheEventAndItsOrder() {
        String no = file("key-00040", request("6")).getBody().get("claimNo").asText();
        edit(no, 1, request("5"));
        doThrow(SdkClientException.create("down")).when(sqs).sendMessage(any(java.util.function.Consumer.class));
        relay.publish();
        assertThat(db.sql("SELECT COUNT(*) FROM outbox WHERE published").query(Integer.class).single()).isZero();
        reset(sqs);
        relay.publish();
        assertThat(db.sql("SELECT COUNT(*) FROM outbox WHERE published").query(Integer.class).single()).isEqualTo(2);
    }

    // helpers

    private ResponseEntity<JsonNode> file(String key, ClaimRequest r) {
        return call(HttpMethod.POST, "/api/v1/claims", dealer("NORTH"), r, "Idempotency-Key", key);
    }

    private ResponseEntity<JsonNode> edit(String no, int version, ClaimRequest r) {
        return call(HttpMethod.PUT, "/api/v1/claims/" + no, dealer("NORTH"), r, "If-Match", "\"" + version + "\"");
    }

    private ResponseEntity<JsonNode> decide(String no, int version, String decision, String token) {
        return call(HttpMethod.POST, "/api/v1/claims/" + no + "/decision", token, java.util.Map.of("decision", decision),
                "If-Match", "\"" + version + "\"");
    }

    private ResponseEntity<JsonNode> call(HttpMethod method, String path, String token, Object body, String... header) {
        var headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        for (int i = 0; i < header.length; i += 2) {
            headers.set(header[i], header[i + 1]);
        }
        return http.exchange(path, method, new HttpEntity<>(body, headers), JsonNode.class);
    }

    private String dealer(String group) {
        return person("dealer-" + group, "DEALER", "GROUP_" + group);
    }

    private String person(String user, String... roles) {
        return ((LocalTokenSource) tokens).personToken(user, List.of(roles));
    }

    private static ClaimRequest request(String hours) {
        return request(hours, "D101");
    }

    private static ClaimRequest request(String hours, String dealer) {
        return new ClaimRequest(dealer, "T40", "SN-1", "S", LocalDate.parse("2017-01-01"), LocalDate.parse("2017-06-01"),
                new BigDecimal("410.5"), "HYD-PUMP", new BigDecimal(hours), new BigDecimal("12.0"), "Pump whines under load",
                List.of(new ClaimRequest.Line("HP-100", 1, new BigDecimal("100.0000"))));
    }

    private void stage(String no, String dealer, int version, int lines, String total) {
        db.sql("""
                INSERT INTO staging.claims VALUES (:n, :d, 'T40', 'SN-9', 'S', '2017-01-01', '2017-06-01', 10, 'HYD-PUMP', 2,
                  NULL, 'from the old system', 'SUBMITTED', :l, :v, 100, 1, 1, 0, :t)""")
                .param("n", no).param("d", dealer).param("l", lines).param("v", version).param("t", total).update();
    }

    private void line(String no, int i) {
        db.sql("INSERT INTO staging.claim_lines VALUES (:n, :i, 'HP-100', 1, 10)").param("n", no).param("i", i).update();
    }

    private void owner(String group, Owner owner) {
        db.sql("UPDATE dealer_group SET owner = :o WHERE group_code = :g").param("o", owner.name()).param("g", group).update();
    }

    private int count(String table) {
        return db.sql("SELECT COUNT(*) FROM " + table).query(Integer.class).single();
    }

    /** The web client is generated from this file. Run with OPENAPI_WRITE=true after changing an endpoint. */
    @Test
    void theCheckedInContractIsCurrent() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper()
                .configure(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
        var tree = mapper.readValue(http.getForObject("/v3/api-docs", String.class), java.util.TreeMap.class);
        tree.remove("servers");
        String now = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(tree) + "\n";
        var file = java.nio.file.Path.of("../../web/api/claims.json");
        if ("true".equals(System.getenv("OPENAPI_WRITE"))) {
            java.nio.file.Files.writeString(file, now);
        }
        assertThat(java.nio.file.Files.readString(file)).isEqualTo(now);
    }
}
