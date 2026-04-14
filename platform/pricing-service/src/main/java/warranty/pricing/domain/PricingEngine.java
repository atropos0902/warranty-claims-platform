package warranty.pricing.domain;

import static java.math.BigDecimal.ZERO;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import warranty.pricing.domain.Quote.Step;

/** Port of the PL/SQL package {@code claim_pricing}. With all flags off it must agree to the cent. */
public final class PricingEngine {
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal PARTS_MARKUP = new BigDecimal("0.10");
    private static final BigDecimal HANDLING_LIMIT = new BigDecimal("150");
    private static final BigDecimal TRAVEL_STEP = new BigDecimal("25");
    private static final BigDecimal TRAVEL_RATE = new BigDecimal("0.85");
    private static final BigDecimal TRAVEL_MAX = new BigDecimal("200");
    private static final BigDecimal FULL = new BigDecimal("100.0");
    private static final BigDecimal NONE = new BigDecimal("0.0");

    private PricingEngine() {}

    public static Quote price(ClaimInput claim, Terms terms, RuleFlags flags) {
        List<Step> steps = new ArrayList<>();
        BigDecimal pct = coverage(claim, terms, flags, steps);
        BigDecimal labor = labor(claim.laborHours(), terms, flags, steps);
        BigDecimal parts = parts(claim.lines(), flags, steps);
        BigDecimal travel = pct.signum() > 0 ? travel(claim.travelMiles(), steps) : new BigDecimal("0.00");

        BigDecimal covered = OracleMath.round(labor.add(parts).multiply(pct).divide(HUNDRED), 2);
        BigDecimal total = covered.subtract(terms.deductible()).max(ZERO).add(travel);
        if (terms.deductible().signum() > 0) {
            steps.add(new Step("deductible", terms.deductible().toPlainString() + " taken off labor and parts"));
        }
        if ("G".equals(claim.claimType())) {
            total = total.setScale(0, RoundingMode.DOWN);
            steps.add(new Step("goodwill", "total cut to whole dollars"));
        }
        return new Quote(pct, labor, parts, travel, total.setScale(2), List.copyOf(steps));
    }

    private static BigDecimal coverage(ClaimInput claim, Terms terms, RuleFlags flags, List<Step> steps) {
        if (claim.meterHours() == null) {
            if (flags.holdMissingMeter()) {
                steps.add(new Step("coverage", "no meter reading, so nothing is paid"));
                return NONE;
            }
        } else if (claim.meterHours().compareTo(terms.meterLimit()) > 0) {
            steps.add(new Step("coverage", "meter is over " + terms.meterLimit().toPlainString() + " hours"));
            return NONE;
        }
        if (flags.rejectFailureBeforePurchase() && claim.failureDate().isBefore(claim.purchaseDate())) {
            steps.add(new Step("coverage", "failure is before purchase"));
            return NONE;
        }
        int months = OracleMath.wholeMonthsBetween(claim.failureDate(), claim.purchaseDate());
        if (months < terms.fullMonths()) {
            steps.add(new Step("coverage", months + " months in service, full coverage"));
            return FULL;
        }
        if (months < terms.endMonths()) {
            BigDecimal pct = HUNDRED.multiply(BigDecimal.valueOf(terms.endMonths() - months))
                    .divide(BigDecimal.valueOf(terms.endMonths() - terms.fullMonths()), 1, RoundingMode.HALF_UP);
            steps.add(new Step("coverage", months + " months in service, " + pct.toPlainString() + "%"));
            return pct;
        }
        steps.add(new Step("coverage", months + " months in service, coverage has ended"));
        return NONE;
    }

    private static BigDecimal labor(BigDecimal hours, Terms terms, RuleFlags flags, List<Step> steps) {
        BigDecimal paid = flags.capBeforeRounding()
                ? OracleMath.roundToHalf(hours.min(terms.capHours()))
                : OracleMath.roundToHalf(hours).min(terms.capHours());
        steps.add(new Step("labor", paid.stripTrailingZeros().toPlainString() + " of "
                + hours.stripTrailingZeros().toPlainString() + " hours paid, cap "
                + terms.capHours().stripTrailingZeros().toPlainString()));
        return OracleMath.round(paid.multiply(terms.laborRate()), 2);
    }

    private static BigDecimal parts(List<ClaimInput.PartLine> lines, RuleFlags flags, List<Step> steps) {
        BigDecimal cost = new BigDecimal("0.00");
        BigDecimal handling = new BigDecimal("0.00");
        for (var line : lines) {
            BigDecimal base = OracleMath.round(BigDecimal.valueOf(line.qty()).multiply(line.unitCost()), 2);
            BigDecimal fee = OracleMath.round(base.multiply(PARTS_MARKUP), 2);
            cost = cost.add(base);
            handling = handling.add(flags.handlingLimitPerClaim() ? fee : fee.min(HANDLING_LIMIT));
        }
        if (flags.handlingLimitPerClaim()) {
            handling = handling.min(HANDLING_LIMIT);
        }
        steps.add(new Step("parts", cost.toPlainString() + " plus " + handling.setScale(2).toPlainString() + " handling"));
        return cost.add(handling).setScale(2);
    }

    private static BigDecimal travel(BigDecimal miles, List<Step> steps) {
        BigDecimal value = miles == null ? ZERO : miles;
        BigDecimal billed = value.divide(TRAVEL_STEP).setScale(0, RoundingMode.CEILING).multiply(TRAVEL_STEP).min(TRAVEL_MAX);
        if (billed.signum() > 0) {
            steps.add(new Step("travel", billed.toPlainString() + " miles billed"));
        }
        return OracleMath.round(billed.multiply(TRAVEL_RATE), 2);
    }
}
