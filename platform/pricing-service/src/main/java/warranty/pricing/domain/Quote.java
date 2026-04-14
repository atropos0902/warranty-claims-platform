package warranty.pricing.domain;

import java.math.BigDecimal;
import java.util.List;

public record Quote(
        BigDecimal coveragePct,
        BigDecimal laborPay,
        BigDecimal partsPay,
        BigDecimal travelPay,
        BigDecimal totalPay,
        List<Step> steps) {

    /** One line of the explanation an adjuster sees under the total. */
    public record Step(String rule, String detail) {}
}
