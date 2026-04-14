package warranty.pricing;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import warranty.pricing.domain.ClaimInput;
import warranty.pricing.domain.ClaimInput.PartLine;
import warranty.pricing.domain.PricingEngine;
import warranty.pricing.domain.Quote;
import warranty.pricing.domain.RuleFlags;
import warranty.pricing.domain.Terms;

class PricingEngineTest {
    private static final LocalDate BOUGHT = LocalDate.parse("2017-04-10");

    static Terms terms() {
        return new Terms(new BigDecimal("100.00"), new BigDecimal("4.25"), 24, 36, new BigDecimal("2000"), BigDecimal.ZERO);
    }

    private static Terms deductible(String amount) {
        return new Terms(new BigDecimal("100.00"), new BigDecimal("4.25"), 24, 36, new BigDecimal("2000"), new BigDecimal(amount));
    }

    private static ClaimInput claim(String hours, String meter, String miles, String type, int months, PartLine... lines) {
        return new ClaimInput("D101", "T40", type, BOUGHT, BOUGHT.plusMonths(months),
                meter == null ? null : new BigDecimal(meter), "HYD-PUMP", new BigDecimal(hours),
                miles == null ? null : new BigDecimal(miles), List.of(lines));
    }

    private static PartLine part(int qty, String cost) {
        return new PartLine(qty, new BigDecimal(cost));
    }

    private static Quote legacy(ClaimInput c) {
        return PricingEngine.price(c, terms(), RuleFlags.LEGACY);
    }

    private static String detail(Quote q, String rule) {
        return q.steps().stream().filter(s -> s.rule().equals(rule)).map(Quote.Step::detail).findFirst().orElse(null);
    }

    @ParameterizedTest
    @CsvSource({
        "6,    425.00, 450.00",
        "4.25, 425.00, 450.00",
        "4.24, 400.00, 400.00",
        "3.75, 400.00, 400.00",
        "3.74, 350.00, 350.00",
        "0,      0.00,   0.00"
    })
    void laborRoundsThenCapsUntilTheFlagIsOn(String hours, BigDecimal legacy, BigDecimal manual) {
        var claim = claim(hours, "10", null, "S", 1);
        assertThat(legacy(claim).laborPay()).isEqualByComparingTo(legacy);
        var flag = new RuleFlags(true, false, false, false);
        assertThat(PricingEngine.price(claim, terms(), flag).laborPay()).isEqualByComparingTo(manual);
    }

    @ParameterizedTest
    @CsvSource({"23, 100.0", "24, 100.0", "25, 91.7", "35, 8.3", "36, 0.0", "60, 0.0", "0, 100.0"})
    void coverageTapersBetweenTheTwoEdges(int months, BigDecimal pct) {
        assertThat(legacy(claim("1", "10", null, "S", months)).coveragePct()).isEqualByComparingTo(pct);
    }

    @ParameterizedTest
    @CsvSource(value = {"2000, 100.0", "2000.1, 0.0", "NULL, 100.0"}, nullValues = "NULL")
    void meterOverTheLimitEndsCoverageAndAMissingOneDoesNot(String meter, BigDecimal pct) {
        assertThat(legacy(claim("1", meter, null, "S", 1)).coveragePct()).isEqualByComparingTo(pct);
    }

    @Test
    void missingMeterPaysNothingOnceTheFlagIsOn() {
        var flag = new RuleFlags(false, true, false, false);
        var q = PricingEngine.price(claim("1", null, "30", "S", 1), terms(), flag);
        assertThat(q.totalPay()).isEqualByComparingTo("0");
        assertThat(detail(q, "coverage")).contains("no meter reading");
        var read = PricingEngine.price(claim("1", "10", null, "S", 1), terms(), flag);
        assertThat(read.totalPay()).isEqualByComparingTo("100");
    }

    @Test
    void failureBeforePurchaseIsPaidUntilTheFlagIsOn() {
        var claim = claim("1", "10", null, "S", -2);
        assertThat(legacy(claim).coveragePct()).isEqualByComparingTo("100");
        var flag = new RuleFlags(false, false, false, true);
        var q = PricingEngine.price(claim, terms(), flag);
        assertThat(q.coveragePct()).isEqualByComparingTo("0");
        assertThat(detail(q, "coverage")).contains("before purchase");
        assertThat(PricingEngine.price(claim("1", "10", null, "S", 0), terms(), flag).coveragePct()).isEqualByComparingTo("100");
    }

    @ParameterizedTest
    @CsvSource({
        "1, 1499.95, 1649.95",
        "1, 1500.00, 1650.00",
        "1, 1500.05, 1650.05",
        "1, 3000.00, 3150.00",
        "3, 0.0050,     0.02",
        "8, 12.345,   108.64",
        "-1, 100.00, -110.00"
    })
    void handlingIsTenPercentUpToTheLimit(int qty, String cost, BigDecimal paid) {
        assertThat(legacy(claim("0", "10", null, "S", 1, part(qty, cost))).partsPay()).isEqualByComparingTo(paid);
    }

    @Test
    void handlingLimitIsPerLineUntilTheFlagIsOn() {
        var claim = claim("0", "10", null, "S", 1, part(1, "2000"), part(1, "2000"));
        assertThat(legacy(claim).partsPay()).isEqualByComparingTo("4300.00");
        var flag = new RuleFlags(false, false, true, false);
        assertThat(PricingEngine.price(claim, terms(), flag).partsPay()).isEqualByComparingTo("4150.00");
        var small = claim("0", "10", null, "S", 1, part(1, "200"), part(1, "300"));
        assertThat(PricingEngine.price(small, terms(), flag).partsPay()).isEqualByComparingTo("550.00");
    }

    @ParameterizedTest
    @CsvSource(value = {"NULL, 0.00", "0, 0.00", "0.1, 21.25", "25, 21.25", "25.1, 42.50", "200, 170.00", "900, 170.00"}, nullValues = "NULL")
    void travelIsBilledInStepsUpToTheMaximum(String miles, BigDecimal paid) {
        var q = legacy(claim("0", "10", miles, "S", 1));
        assertThat(q.travelPay()).isEqualByComparingTo(paid);
        assertThat(q.totalPay()).isEqualByComparingTo(paid);
        assertThat(detail(q, "travel") != null).isEqualTo(paid.signum() > 0);
    }

    @Test
    void travelIsNotPaidWithoutCoverage() {
        var q = legacy(claim("2", "10", "100", "S", 40));
        assertThat(q.travelPay()).isEqualByComparingTo("0");
        assertThat(q.totalPay()).isEqualByComparingTo("0");
    }

    @Test
    void deductibleComesOffLaborAndPartsButNotTravel() {
        var q = PricingEngine.price(claim("1", "10", "25", "E", 1), deductible("250"), RuleFlags.LEGACY);
        assertThat(q.totalPay()).isEqualByComparingTo("21.25");
        assertThat(detail(q, "deductible")).startsWith("250");
        var bigger = PricingEngine.price(claim("4", "10", "25", "E", 1), deductible("250"), RuleFlags.LEGACY);
        assertThat(bigger.totalPay()).isEqualByComparingTo("171.25");
        assertThat(detail(legacy(claim("1", "10", null, "S", 1)), "deductible")).isNull();
    }

    @Test
    void goodwillDropsTheCents() {
        var claim = claim("1", "10", "25", "G", 1, part(1, "10.65"));
        assertThat(legacy(claim).totalPay()).isEqualByComparingTo("132.00");
        assertThat(detail(legacy(claim), "goodwill")).contains("whole dollars");
        assertThat(legacy(claim("1", "10", "25", "S", 1, part(1, "10.65"))).totalPay()).isEqualByComparingTo("132.97");
    }

    @Test
    void taperIsAppliedToLaborAndPartsTogether() {
        var q = legacy(claim("1", "10", null, "S", 25, part(1, "33.33")));
        assertThat(q.partsPay()).isEqualByComparingTo("36.66");
        assertThat(q.totalPay()).isEqualByComparingTo("125.32");
    }

    @Test
    void explanationListsTheStepsInOrder() {
        var q = legacy(claim("6", "10", "30", "S", 25, part(2, "10")));
        assertThat(q.steps()).extracting(Quote.Step::rule).containsExactly("coverage", "labor", "parts", "travel");
        assertThat(detail(q, "coverage")).isEqualTo("25 months in service, 91.7%");
        assertThat(detail(q, "labor")).isEqualTo("4.25 of 6 hours paid, cap 4.25");
        assertThat(detail(q, "parts")).isEqualTo("20.00 plus 2.00 handling");
        assertThat(detail(q, "travel")).isEqualTo("50 miles billed");
        assertThat(detail(legacy(claim("1", "2000.1", null, "S", 1)), "coverage")).isEqualTo("meter is over 2000 hours");
        assertThat(detail(legacy(claim("1", "10", null, "S", 40)), "coverage")).isEqualTo("40 months in service, coverage has ended");
    }
}
