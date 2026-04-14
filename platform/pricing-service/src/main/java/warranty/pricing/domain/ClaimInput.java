package warranty.pricing.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** What a price depends on. Meter hours and travel miles may be missing, as they are in the old data. */
public record ClaimInput(
        String dealerCode,
        String modelCode,
        String claimType,
        LocalDate purchaseDate,
        LocalDate failureDate,
        BigDecimal meterHours,
        String repairCode,
        BigDecimal laborHours,
        BigDecimal travelMiles,
        List<PartLine> lines) {

    public record PartLine(int qty, BigDecimal unitCost) {}
}
