package warranty.migration;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import warranty.migration.Events.Claim;
import warranty.migration.MigrationApplication.Database;

/** Writes to and reads from the old Oracle schema. */
@Component
class LegacyClaims {
    private final Database legacy;

    LegacyClaims(Database legacy) {
        this.legacy = legacy;
    }

    record Version(String claimNo, int version, String status) {}

    record Price(BigDecimal coveragePct, BigDecimal laborPay, BigDecimal partsPay, BigDecimal travelPay,
                 BigDecimal totalPay) {}

    /**
     * Applies one platform event. A repeated event stops at the inbox and an older one stops at the
     * version check, so delivery order and repeats do not matter. Returns the PL/SQL price for this
     * version, or null when Oracle already holds a newer one.
     */
    Price replay(String eventId, Claim c) {
        return legacy.tx().execute(s -> {
            try {
                legacy.sql().sql("INSERT INTO replay_inbox VALUES (:e)").param("e", eventId).update();
                apply(c);
            } catch (DuplicateKeyException e) {
                // Seen before. The comparison may still be missing, so carry on and read the price.
            }
            return legacy.sql().sql("""
                    SELECT coverage_pct, labor_pay, parts_pay, travel_pay, total_pay FROM claims
                     WHERE claim_no = :n AND version_no = :v""")
                    .param("n", c.claimNo()).param("v", c.version()).query(Price.class).optional().orElse(null);
        });
    }

    private void apply(Claim c) {
        Integer have = legacy.sql().sql("SELECT version_no FROM claims WHERE claim_no = :n FOR UPDATE")
                .param("n", c.claimNo()).query(Integer.class).optional().orElse(null);
        if (have != null && have >= c.version()) {
            return;
        }
        String sql = have == null ? """
                INSERT INTO claims (claim_no, dealer_code, model_code, serial_no, claim_type, purchase_date, failure_date,
                                    meter_hours, repair_code, labor_hours, travel_miles, notes, status, line_count, version_no)
                VALUES (:n, :dealer, :model, :serial, :type, :purchase, :failure, :meter, :repair, :labor, :miles, :notes,
                        :status, :lines, :version)""" : """
                UPDATE claims SET dealer_code = :dealer, model_code = :model, serial_no = :serial, claim_type = :type,
                       purchase_date = :purchase, failure_date = :failure, meter_hours = :meter, repair_code = :repair,
                       labor_hours = :labor, travel_miles = :miles, notes = :notes, status = :status,
                       line_count = :lines, version_no = :version
                 WHERE claim_no = :n""";
        legacy.sql().sql(sql).param("n", c.claimNo()).param("dealer", c.dealerCode()).param("model", c.modelCode())
                .param("serial", c.serialNo()).param("type", c.claimType())
                .param("purchase", Date.valueOf(c.purchaseDate())).param("failure", Date.valueOf(c.failureDate()))
                .param("meter", c.meterHours()).param("repair", c.repairCode()).param("labor", c.laborHours())
                .param("miles", c.travelMiles()).param("notes", c.notes()).param("status", c.status())
                .param("lines", c.lines().size()).param("version", c.version()).update();
        legacy.sql().sql("DELETE FROM claim_lines WHERE claim_no = :n").param("n", c.claimNo()).update();
        int i = 1;
        for (var l : c.lines()) {
            legacy.sql().sql("INSERT INTO claim_lines VALUES (:n, :i, :p, :q, :u)").param("n", c.claimNo()).param("i", i++)
                    .param("p", l.partNo()).param("q", l.qty()).param("u", l.unitCost()).update();
        }
        legacy.sql().sql("BEGIN claim_pricing.price_claim(:n); END;").param("n", c.claimNo()).update();
    }

    List<Version> versions(String group) {
        return legacy.sql().sql("""
                SELECT c.claim_no, c.version_no AS version, c.status FROM claims c
                  JOIN dealers d ON d.dealer_code = c.dealer_code WHERE d.group_code = :g ORDER BY c.claim_no""")
                .param("g", group).query((rs, i) -> new Version(rs.getString(1), rs.getInt(2), rs.getString(3))).list();
    }

    /** Waits for writes in flight, because each of them holds a lock on this row. */
    void allowWrites(String group, boolean allowed) {
        legacy.sql().sql("""
                MERGE INTO group_control g USING (SELECT :g AS code, :a AS allowed FROM dual) n ON (g.group_code = n.code)
                WHEN MATCHED THEN UPDATE SET g.writes_allowed = n.allowed
                WHEN NOT MATCHED THEN INSERT (group_code, writes_allowed) VALUES (n.code, n.allowed)""")
                .param("g", group).param("a", allowed ? "Y" : "N").update();
    }

    record Header(String claimNo, String dealerCode, String modelCode, String serialNo, String claimType,
                  LocalDate purchaseDate, LocalDate failureDate, BigDecimal meterHours, String repairCode,
                  BigDecimal laborHours, BigDecimal travelMiles, String notes, String status, int lineCount,
                  int versionNo, BigDecimal coveragePct, BigDecimal laborPay, BigDecimal partsPay, BigDecimal travelPay,
                  BigDecimal totalPay) {}

    record Line(int lineNo, String partNo, int qty, BigDecimal unitCost) {}

    record Change(long seq, String claimNo) {}

    record Copy(List<Change> changes, List<Header> headers, List<List<Line>> lines) {}

    /** One consistent read of the next changes. */
    Copy changesAfter(long seq, int limit) {
        return legacy.tx().execute(s -> {
            legacy.sql().sql("SET TRANSACTION READ ONLY").update();
            List<Change> changes = legacy.sql().sql("""
                    SELECT seq, claim_no FROM claim_change_log WHERE seq > :s ORDER BY seq FETCH FIRST :l ROWS ONLY""")
                    .param("s", seq).param("l", limit).query((rs, i) -> new Change(rs.getLong(1), rs.getString(2))).list();
            List<String> numbers = changes.stream().map(Change::claimNo).distinct().toList();
            List<Header> headers = numbers.stream().map(this::header).toList();
            return new Copy(changes, headers, numbers.stream().map(this::lines).toList());
        });
    }

    private Header header(String claimNo) {
        return legacy.sql().sql("SELECT * FROM claims WHERE claim_no = :n").param("n", claimNo)
                .query((rs, i) -> new Header(rs.getString("claim_no"), rs.getString("dealer_code"), rs.getString("model_code"),
                        rs.getString("serial_no"), rs.getString("claim_type"), rs.getDate("purchase_date").toLocalDate(),
                        rs.getDate("failure_date").toLocalDate(), rs.getBigDecimal("meter_hours"),
                        rs.getString("repair_code"), rs.getBigDecimal("labor_hours"), rs.getBigDecimal("travel_miles"),
                        rs.getString("notes"), rs.getString("status"), rs.getInt("line_count"), rs.getInt("version_no"),
                        rs.getBigDecimal("coverage_pct"), rs.getBigDecimal("labor_pay"), rs.getBigDecimal("parts_pay"),
                        rs.getBigDecimal("travel_pay"), rs.getBigDecimal("total_pay"))).single();
    }

    private List<Line> lines(String claimNo) {
        return legacy.sql().sql("SELECT line_no, part_no, qty, unit_cost FROM claim_lines WHERE claim_no = :n ORDER BY line_no")
                .param("n", claimNo)
                .query((rs, i) -> new Line(rs.getInt(1), rs.getString(2), rs.getInt(3), rs.getBigDecimal(4))).list();
    }
}
