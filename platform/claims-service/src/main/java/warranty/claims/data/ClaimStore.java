package warranty.claims.data;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import warranty.claims.api.ClaimRequest;
import warranty.claims.api.ClaimSummary;
import warranty.claims.api.ClaimView;

@Repository
public class ClaimStore {
    private static final String SELECT = """
            SELECT c.*, d.group_code FROM claim c JOIN dealer d ON d.dealer_code = c.dealer_code WHERE c.claim_no = :n""";
    private final JdbcClient db;
    private final ObjectMapper json;

    public ClaimStore(JdbcClient db, ObjectMapper json) {
        this.db = db;
        this.json = json;
    }

    public Optional<ClaimView> find(String claimNo) {
        return db.sql(SELECT).param("n", claimNo).query((rs, i) -> view(rs)).optional();
    }

    public Optional<ClaimView> lock(String claimNo) {
        return db.sql(SELECT + " FOR UPDATE OF c").param("n", claimNo).query((rs, i) -> view(rs)).optional();
    }

    public List<ClaimSummary> list(String groupLimit, String status, String serialNo, String after) {
        return db.sql("""
                SELECT c.claim_no, c.dealer_code, d.dealer_name, d.group_code, c.model_code, c.serial_no, c.repair_code,
                       c.status, c.total_pay
                  FROM claim c JOIN dealer d ON d.dealer_code = c.dealer_code
                 WHERE (:g IS NULL OR d.group_code = :g) AND (:s IS NULL OR c.status = :s)
                   AND (:sn IS NULL OR c.serial_no = :sn) AND (:a IS NULL OR c.claim_no > :a)
                 ORDER BY c.claim_no LIMIT 50""")
                .param("g", groupLimit).param("s", status).param("sn", serialNo).param("a", after)
                .query(ClaimSummary.class).list();
    }

    public void insert(String claimNo, ClaimRequest r, String status, int version) {
        fields(db.sql("""
                INSERT INTO claim (claim_no, dealer_code, model_code, serial_no, claim_type, purchase_date, failure_date,
                                   meter_hours, repair_code, labor_hours, travel_miles, notes, status, version_no)
                VALUES (:n, :dealer, :model, :serial, :type, :purchase, :failure, :meter, :repair, :labor, :miles, :notes,
                        :status, :version)"""), claimNo, r).param("status", status).param("version", version).update();
        insertLines(claimNo, r.lines());
    }

    /** Clears the price, because the old one no longer describes the claim. */
    public void update(String claimNo, ClaimRequest r, String status, int version) {
        fields(db.sql("""
                UPDATE claim SET dealer_code = :dealer, model_code = :model, serial_no = :serial, claim_type = :type,
                       purchase_date = :purchase, failure_date = :failure, meter_hours = :meter, repair_code = :repair,
                       labor_hours = :labor, travel_miles = :miles, notes = :notes, status = :status,
                       version_no = :version, coverage_pct = NULL, labor_pay = NULL, parts_pay = NULL,
                       travel_pay = NULL, total_pay = NULL, price_steps = NULL
                 WHERE claim_no = :n"""), claimNo, r).param("status", status).param("version", version).update();
        db.sql("DELETE FROM claim_line WHERE claim_no = :n").param("n", claimNo).update();
        insertLines(claimNo, r.lines());
    }

    public void price(String claimNo, ClaimView.Price p) {
        db.sql("""
                UPDATE claim SET coverage_pct = :c, labor_pay = :l, parts_pay = :p, travel_pay = :t, total_pay = :total,
                       price_steps = :steps WHERE claim_no = :n""")
                .param("c", p.coveragePct()).param("l", p.laborPay()).param("p", p.partsPay()).param("t", p.travelPay())
                .param("total", p.totalPay()).param("steps", write(p.steps())).param("n", claimNo).update();
    }

    public void decide(String claimNo, String status, String note, String who, int version) {
        db.sql("""
                UPDATE claim SET status = :s, decision_note = :note, decided_by = :w, version_no = :v
                 WHERE claim_no = :n""")
                .param("s", status).param("note", note).param("w", who).param("v", version).param("n", claimNo).update();
    }

    public List<String> withoutPrice() {
        return db.sql("""
                SELECT c.claim_no FROM claim c JOIN dealer d ON d.dealer_code = c.dealer_code
                  JOIN dealer_group g ON g.group_code = d.group_code
                 WHERE c.status = 'SUBMITTED' AND c.total_pay IS NULL AND g.owner = 'PLATFORM' LIMIT 100""")
                .query(String.class).list();
    }

    private static JdbcClient.StatementSpec fields(JdbcClient.StatementSpec s, String claimNo, ClaimRequest r) {
        return s.param("n", claimNo).param("dealer", r.dealerCode()).param("model", r.modelCode())
                .param("serial", r.serialNo()).param("type", r.claimType()).param("purchase", r.purchaseDate())
                .param("failure", r.failureDate()).param("meter", r.meterHours()).param("repair", r.repairCode())
                .param("labor", r.laborHours()).param("miles", r.travelMiles()).param("notes", r.notes());
    }

    private void insertLines(String claimNo, List<ClaimRequest.Line> lines) {
        int n = 1;
        for (var l : lines) {
            db.sql("INSERT INTO claim_line VALUES (:c, :n, :p, :q, :u)").param("c", claimNo).param("n", n++)
                    .param("p", l.partNo()).param("q", l.qty()).param("u", l.unitCost()).update();
        }
    }

    private List<ClaimRequest.Line> lines(String claimNo) {
        return db.sql("SELECT part_no, qty, unit_cost FROM claim_line WHERE claim_no = :n ORDER BY line_no")
                .param("n", claimNo).query(ClaimRequest.Line.class).list();
    }

    private ClaimView view(ResultSet rs) throws SQLException {
        String no = rs.getString("claim_no");
        ClaimView.Price price = rs.getBigDecimal("total_pay") == null ? null : new ClaimView.Price(
                rs.getBigDecimal("coverage_pct"), rs.getBigDecimal("labor_pay"), rs.getBigDecimal("parts_pay"),
                rs.getBigDecimal("travel_pay"), rs.getBigDecimal("total_pay"), steps(rs.getString("price_steps")));
        return new ClaimView(no, rs.getString("group_code"), rs.getString("dealer_code"), rs.getString("model_code"),
                rs.getString("serial_no"), rs.getString("claim_type"), rs.getObject("purchase_date", java.time.LocalDate.class),
                rs.getObject("failure_date", java.time.LocalDate.class), rs.getBigDecimal("meter_hours"),
                rs.getString("repair_code"), rs.getBigDecimal("labor_hours"), rs.getBigDecimal("travel_miles"),
                rs.getString("notes"), rs.getString("status"), rs.getInt("version_no"), lines(no), price,
                rs.getString("decision_note"));
    }

    private List<ClaimView.Step> steps(String text) {
        try {
            return text == null ? List.of() : json.readValue(text, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
