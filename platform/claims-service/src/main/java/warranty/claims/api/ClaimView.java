package warranty.claims.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record ClaimView(
        String claimNo,
        String groupCode,
        String dealerCode,
        String modelCode,
        String serialNo,
        String claimType,
        LocalDate purchaseDate,
        LocalDate failureDate,
        BigDecimal meterHours,
        String repairCode,
        BigDecimal laborHours,
        BigDecimal travelMiles,
        String notes,
        String status,
        int version,
        List<ClaimRequest.Line> lines,
        Price price,
        String decisionNote) {

    /** Null until pricing has answered. */
    public record Price(BigDecimal coveragePct, BigDecimal laborPay, BigDecimal partsPay, BigDecimal travelPay,
                        BigDecimal totalPay, List<Step> steps) {}

    public record Step(String rule, String detail) {}

    public ClaimRequest toRequest() {
        return new ClaimRequest(dealerCode, modelCode, serialNo, claimType, purchaseDate, failureDate, meterHours,
                repairCode, laborHours, travelMiles, notes, lines);
    }
}
