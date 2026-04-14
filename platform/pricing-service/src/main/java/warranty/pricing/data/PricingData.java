package warranty.pricing.data;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import warranty.pricing.domain.ClaimInput;
import warranty.pricing.domain.RuleFlags;
import warranty.pricing.domain.Terms;

@Repository
public class PricingData {
    private final JdbcClient db;

    public PricingData(JdbcClient db) {
        this.db = db;
    }

    public Optional<Terms> terms(ClaimInput claim) {
        return db.sql("""
                SELECT d.labor_rate, r.cap_hours, t.full_months, t.end_months, t.meter_limit, t.deductible
                  FROM dealer_rate d
                  JOIN repair_code r ON r.repair_code = :repair
                  JOIN warranty_term t ON t.model_code = :model AND t.claim_type = :type
                 WHERE d.dealer_code = :dealer""")
                .param("repair", claim.repairCode()).param("model", claim.modelCode())
                .param("type", claim.claimType()).param("dealer", claim.dealerCode())
                .query((rs, i) -> new Terms(rs.getBigDecimal(1), rs.getBigDecimal(2), rs.getInt(3), rs.getInt(4),
                        rs.getBigDecimal(5), rs.getBigDecimal(6)))
                .optional();
    }

    public RuleFlags flags() {
        Map<String, Boolean> f = new java.util.HashMap<>();
        db.sql("SELECT flag_name, enabled FROM rule_flag").query(rs -> {
            f.put(rs.getString(1), rs.getBoolean(2));
        });
        return new RuleFlags(
                f.getOrDefault("cap-before-rounding", false),
                f.getOrDefault("hold-missing-meter", false),
                f.getOrDefault("handling-limit-per-claim", false),
                f.getOrDefault("reject-failure-before-purchase", false));
    }

    public Reference reference() {
        return new Reference(
                db.sql("SELECT DISTINCT model_code FROM warranty_term ORDER BY 1").query(String.class).list(),
                db.sql("SELECT repair_code, description, cap_hours FROM repair_code ORDER BY description")
                        .query(RepairCode.class).list());
    }

    public List<WarrantyTerm> termsForModel(String model) {
        return db.sql("""
                SELECT model_code, claim_type, full_months, end_months, meter_limit, deductible
                  FROM warranty_term WHERE model_code = :m ORDER BY claim_type""")
                .param("m", model).query(WarrantyTerm.class).list();
    }

    public record RepairCode(String repairCode, String description, java.math.BigDecimal capHours) {}

    public record Reference(List<String> models, List<RepairCode> repairCodes) {}

    public record WarrantyTerm(String modelCode, String claimType, int fullMonths, int endMonths,
                               java.math.BigDecimal meterLimit, java.math.BigDecimal deductible) {}
}
