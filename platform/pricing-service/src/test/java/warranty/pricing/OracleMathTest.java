package warranty.pricing;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import warranty.pricing.domain.ClaimInput;
import warranty.pricing.domain.PricingEngine;
import warranty.pricing.domain.RuleFlags;

class OracleMathTest {

    /** Expected values were read from Oracle: TRUNC(MONTHS_BETWEEN(failure, purchase)). */
    @ParameterizedTest
    @CsvSource({
        "2017-01-31, 2017-02-28, 1",
        "2017-01-30, 2017-02-28, 0",
        "2017-02-28, 2017-03-31, 1",
        "2017-02-28, 2017-03-28, 1",
        "2016-02-29, 2017-02-28, 12",
        "2016-02-28, 2017-02-28, 12",
        "2017-01-15, 2017-02-14, 0",
        "2017-01-15, 2017-02-15, 1",
        "2017-03-15, 2017-01-20, -1",
        "2017-03-20, 2017-01-15, -2",
        "2017-03-31, 2017-02-28, -1"
    })
    void monthsFollowOracle(LocalDate purchase, LocalDate failure, int months) {
        var claim = new ClaimInput("D101", "T40", "S", purchase, failure, BigDecimal.ONE, "HYD-PUMP", BigDecimal.ONE, null,
                java.util.List.of());
        var quote = PricingEngine.price(claim, PricingEngineTest.terms(), RuleFlags.LEGACY);
        assertThat(quote.steps().getFirst().detail()).startsWith(months + " months");
    }
}
