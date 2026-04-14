package warranty.pricing.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

/** Oracle arithmetic that Java does differently. */
final class OracleMath {
    private static final BigDecimal TWO = BigDecimal.valueOf(2);

    private OracleMath() {}

    /** ROUND(x, scale): halves go away from zero. */
    static BigDecimal round(BigDecimal x, int scale) {
        return x.setScale(scale, RoundingMode.HALF_UP);
    }

    /** ROUND(x * 2) / 2. */
    static BigDecimal roundToHalf(BigDecimal x) {
        return round(x.multiply(TWO), 0).divide(TWO);
    }

    /**
     * TRUNC(MONTHS_BETWEEN(later, earlier)). Two month ends count as whole months even when the day
     * numbers differ, so 31 January to 28 February is one month here and zero in java.time. The
     * fraction is in 31sts of a month and truncation is toward zero.
     */
    static int wholeMonthsBetween(LocalDate later, LocalDate earlier) {
        int months = (later.getYear() - earlier.getYear()) * 12 + later.getMonthValue() - earlier.getMonthValue();
        boolean bothMonthEnds = isMonthEnd(later) && isMonthEnd(earlier);
        int days = bothMonthEnds ? 0 : later.getDayOfMonth() - earlier.getDayOfMonth();
        return (months * 31 + days) / 31;
    }

    private static boolean isMonthEnd(LocalDate d) {
        return d.getDayOfMonth() == d.lengthOfMonth();
    }
}
