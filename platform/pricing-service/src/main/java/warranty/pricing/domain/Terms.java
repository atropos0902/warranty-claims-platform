package warranty.pricing.domain;

import java.math.BigDecimal;

/** Reference values looked up for one claim. */
public record Terms(
        BigDecimal laborRate,
        BigDecimal capHours,
        int fullMonths,
        int endMonths,
        BigDecimal meterLimit,
        BigDecimal deductible) {}
