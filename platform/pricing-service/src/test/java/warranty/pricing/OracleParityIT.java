package warranty.pricing;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import warranty.pricing.domain.PricingEngine;
import warranty.pricing.domain.Quote;
import warranty.pricing.domain.RuleFlags;

/**
 * Runs the real PL/SQL package against every synthetic claim and compares it with the Java port. Needs
 * an Oracle holding the legacy schema, see {@code make parity}. With {@code GOLDEN_OUT} set it also
 * writes the results file that the faster tests read.
 */
@EnabledIfEnvironmentVariable(named = "LEGACY_DB_URL", matches = ".+")
class OracleParityIT {

    @Test
    void javaPortAgreesWithPlsqlOnEveryClaim() throws Exception {
        List<ClaimFile.Row> claims = ClaimFile.claims();
        Map<String, BigDecimal[]> legacy = new HashMap<>();
        try (Connection c = DriverManager.getConnection(System.getenv("LEGACY_DB_URL"),
                System.getenv("LEGACY_DB_USER"), System.getenv("LEGACY_DB_PASSWORD"))) {
            c.setAutoCommit(false);
            load(c, claims);
            try (var call = c.prepareCall("{call claim_pricing.price_all}")) {
                call.execute();
            }
            try (var ps = c.prepareStatement("SELECT claim_no, coverage_pct, labor_pay, parts_pay, travel_pay, total_pay"
                    + " FROM claims WHERE claim_no LIKE 'S%' ORDER BY claim_no"); var rs = ps.executeQuery()) {
                while (rs.next()) {
                    legacy.put(rs.getString(1), new BigDecimal[] {rs.getBigDecimal(2), rs.getBigDecimal(3),
                        rs.getBigDecimal(4), rs.getBigDecimal(5), rs.getBigDecimal(6)});
                }
            }
            c.rollback();
        }
        List<String> differences = new ArrayList<>();
        for (var row : claims) {
            Quote q = PricingEngine.price(row.input(), ClaimFile.terms(row.input()), RuleFlags.LEGACY);
            BigDecimal[] java = {q.coveragePct(), q.laborPay(), q.partsPay(), q.travelPay(), q.totalPay()};
            BigDecimal[] old = legacy.get(row.claimNo());
            for (int i = 0; i < java.length; i++) {
                if (java[i].compareTo(old[i]) != 0) {
                    differences.add(row.claimNo() + " field " + i + " legacy " + old[i] + " java " + java[i]);
                }
            }
        }
        String out = System.getenv("GOLDEN_OUT");
        if (out != null) {
            var text = new StringBuilder();
            for (var row : claims) {
                text.append(row.claimNo());
                for (BigDecimal v : legacy.get(row.claimNo())) {
                    text.append(',').append(v.toPlainString());
                }
                text.append('\n');
            }
            Files.writeString(Path.of(out), text);
        }
        System.out.println("parity: " + claims.size() + " claims, " + differences.size() + " differing fields");
        assertThat(claims).hasSize(10_000);
        assertThat(differences).isEmpty();
    }

    private static void load(Connection c, List<ClaimFile.Row> claims) throws Exception {
        try (PreparedStatement claim = c.prepareStatement("INSERT INTO claims (claim_no, dealer_code, model_code,"
                + " serial_no, claim_type, purchase_date, failure_date, meter_hours, repair_code, labor_hours,"
                + " travel_miles, line_count) VALUES (?,?,?,'SYN',?,?,?,?,?,?,?,?)");
             PreparedStatement line = c.prepareStatement("INSERT INTO claim_lines VALUES (?,?,'PART',?,?)")) {
            int pending = 0;
            for (var row : claims) {
                var in = row.input();
                claim.setString(1, row.claimNo());
                claim.setString(2, in.dealerCode());
                claim.setString(3, in.modelCode());
                claim.setString(4, in.claimType());
                claim.setDate(5, Date.valueOf(in.purchaseDate()));
                claim.setDate(6, Date.valueOf(in.failureDate()));
                number(claim, 7, in.meterHours());
                claim.setString(8, in.repairCode());
                claim.setBigDecimal(9, in.laborHours());
                number(claim, 10, in.travelMiles());
                claim.setInt(11, in.lines().size());
                claim.addBatch();
                int n = 1;
                for (var l : in.lines()) {
                    line.setString(1, row.claimNo());
                    line.setInt(2, n++);
                    line.setInt(3, l.qty());
                    line.setBigDecimal(4, l.unitCost());
                    line.addBatch();
                }
                if (++pending % 500 == 0) {
                    claim.executeBatch();
                    line.executeBatch();
                }
            }
            claim.executeBatch();
            line.executeBatch();
        }
    }

    private static void number(PreparedStatement ps, int i, BigDecimal v) throws Exception {
        if (v == null) {
            ps.setNull(i, Types.NUMERIC);
        } else {
            ps.setBigDecimal(i, v);
        }
    }
}
