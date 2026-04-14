package warranty.pricing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.LinkedHashMap;
import org.junit.jupiter.api.Test;
import warranty.pricing.domain.OracleMathAccess;
import warranty.pricing.domain.PricingEngine;
import warranty.pricing.domain.RuleFlags;

/** The synthetic claims have to notice each known gap. If a count here drops to zero, parity proves nothing about it. */
class ParityHasTeethTest {

    @Test
    void everyManualRuleChangesSomePayouts() {
        Map<String, RuleFlags> flags = new LinkedHashMap<>();
        flags.put("cap before rounding", new RuleFlags(true, false, false, false));
        flags.put("hold missing meter", new RuleFlags(false, true, false, false));
        flags.put("handling limit per claim", new RuleFlags(false, false, true, false));
        flags.put("reject failure before purchase", new RuleFlags(false, false, false, true));
        var golden = ClaimFile.golden();
        flags.forEach((name, f) -> {
            long changed = ClaimFile.claims().stream()
                    .filter(r -> PricingEngine.price(r.input(), ClaimFile.terms(r.input()), f).totalPay()
                            .compareTo(golden.get(r.claimNo()).total()) != 0)
                    .count();
            System.out.println("gap: " + name + " changes " + changed + " of 10000 payouts");
            assertThat(changed).as(name).isGreaterThan(20);
        });
    }

    @Test
    void javaTimeMonthsWouldDisagree() {
        long changed = ClaimFile.claims().stream()
                .filter(r -> OracleMathAccess.months(r.input().failureDate(), r.input().purchaseDate())
                        != java.time.temporal.ChronoUnit.MONTHS.between(r.input().purchaseDate(), r.input().failureDate()))
                .count();
        System.out.println("gap: java.time month count differs on " + changed + " of 10000 claims");
        assertThat(changed).isGreaterThan(20);
    }
}
