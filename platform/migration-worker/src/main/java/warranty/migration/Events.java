package warranty.migration;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Messages the claims service publishes. */
final class Events {
    private Events() {}

    record Envelope(String eventId, JsonNode body) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Claim(String claimNo, String groupCode, String dealerCode, String modelCode, String serialNo, String claimType,
                 LocalDate purchaseDate, LocalDate failureDate, BigDecimal meterHours, String repairCode,
                 BigDecimal laborHours, BigDecimal travelMiles, String notes, String status, int version,
                 List<Line> lines) {}

    record Line(String partNo, int qty, BigDecimal unitCost) {}
}
