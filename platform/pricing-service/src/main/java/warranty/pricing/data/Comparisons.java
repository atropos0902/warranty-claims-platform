package warranty.pricing.data;

import java.math.BigDecimal;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import warranty.pricing.domain.Quote;

/** Results of pricing one claim in both systems. */
@Repository
public class Comparisons {
    private final JdbcClient db;

    public Comparisons(JdbcClient db) {
        this.db = db;
    }

    /** Keeps the newest version of each claim, so a late or repeated message cannot bring back an old result. */
    public void save(String claimNo, String groupCode, int version, LegacyPrice legacy, Quote quote, String splitBy,
                     String inputJson) {
        boolean differs = splitBy != null;
        db.sql("""
                INSERT INTO comparison (claim_no, group_code, claim_version, legacy_total, platform_total, differs,
                                        split_by, input_json)
                VALUES (:c, :g, :v, :lt, :pt, :d, :s, :j) AS n
                ON DUPLICATE KEY UPDATE
                  explanation    = IF(n.claim_version > comparison.claim_version, NULL, explanation),
                  explained_by   = IF(n.claim_version > comparison.claim_version, NULL, explained_by),
                  group_code     = IF(n.claim_version >= comparison.claim_version, n.group_code, comparison.group_code),
                  legacy_total   = IF(n.claim_version >= comparison.claim_version, n.legacy_total, comparison.legacy_total),
                  platform_total = IF(n.claim_version >= comparison.claim_version, n.platform_total, comparison.platform_total),
                  differs        = IF(n.claim_version >= comparison.claim_version, n.differs, comparison.differs),
                  split_by       = IF(n.claim_version >= comparison.claim_version, n.split_by, comparison.split_by),
                  input_json     = IF(n.claim_version >= comparison.claim_version, n.input_json, comparison.input_json),
                  claim_version  = GREATEST(n.claim_version, comparison.claim_version)""")
                .param("c", claimNo).param("g", groupCode).param("v", version)
                .param("lt", legacy.totalPay()).param("pt", quote.totalPay())
                .param("d", differs).param("s", splitBy).param("j", inputJson).update();
    }

    public List<Difference> open(String groupCode) {
        return db.sql("""
                SELECT claim_no, group_code, claim_version, legacy_total, platform_total, split_by, input_json
                  FROM comparison
                 WHERE differs AND explanation IS NULL AND (:g IS NULL OR group_code = :g)
                 ORDER BY claim_no LIMIT 500""")
                .param("g", groupCode).query(Difference.class).list();
    }

    public int explain(String claimNo, int version, String reason, String who) {
        return db.sql("""
                UPDATE comparison SET explanation = :r, explained_by = :w
                 WHERE claim_no = :c AND claim_version = :v AND differs""")
                .param("r", reason).param("w", who).param("c", claimNo).param("v", version).update();
    }

    public Readiness readiness(String groupCode) {
        return db.sql("""
                SELECT :g AS group_code, COUNT(*) AS compared,
                       COALESCE(SUM(differs AND explanation IS NULL), 0) AS open_differences,
                       COALESCE(SUM(claim_version), 0) AS version_total
                  FROM comparison WHERE group_code = :g""")
                .param("g", groupCode)
                .query((rs, i) -> new Readiness(rs.getString(1), rs.getInt(2), rs.getInt(3),
                        rs.getInt(2) > 0 && rs.getInt(3) == 0, rs.getLong(4)))
                .single();
    }

    public record LegacyPrice(BigDecimal coveragePct, BigDecimal laborPay, BigDecimal partsPay, BigDecimal travelPay,
                              BigDecimal totalPay) {}

    public record Difference(String claimNo, String groupCode, int claimVersion, BigDecimal legacyTotal,
                             BigDecimal platformTotal, String splitBy, String inputJson) {}

    /** The version total lets the mover see that the newest version of every claim has been compared. */
    public record Readiness(String groupCode, int compared, int openDifferences, boolean ready, long versionTotal) {}
}
