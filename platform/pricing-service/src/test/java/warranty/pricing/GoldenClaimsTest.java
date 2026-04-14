package warranty.pricing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import warranty.pricing.domain.PricingEngine;
import warranty.pricing.domain.RuleFlags;

/** Same comparison as OracleParityIT, against results saved from the PL/SQL, so it runs without Oracle. */
class GoldenClaimsTest {

    @Test
    void everyClaimMatchesTheSavedLegacyResult() {
        var golden = ClaimFile.golden();
        List<String> differences = new ArrayList<>();
        for (var row : ClaimFile.claims()) {
            var q = PricingEngine.price(row.input(), ClaimFile.terms(row.input()), RuleFlags.LEGACY);
            var old = golden.get(row.claimNo());
            if (q.coveragePct().compareTo(old.pct()) != 0 || q.laborPay().compareTo(old.labor()) != 0
                    || q.partsPay().compareTo(old.parts()) != 0 || q.travelPay().compareTo(old.travel()) != 0
                    || q.totalPay().compareTo(old.total()) != 0) {
                differences.add(row.claimNo() + " " + old + " " + q);
            }
        }
        assertThat(differences).isEmpty();
    }
}
