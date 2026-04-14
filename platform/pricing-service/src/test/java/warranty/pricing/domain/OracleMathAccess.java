package warranty.pricing.domain;

import java.time.LocalDate;

/** Lets tests outside the package reach the month arithmetic. */
public final class OracleMathAccess {
    private OracleMathAccess() {}

    public static int months(LocalDate later, LocalDate earlier) {
        return OracleMath.wholeMonthsBetween(later, earlier);
    }
}
