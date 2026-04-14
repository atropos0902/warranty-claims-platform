package warranty.pricing.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import warranty.pricing.domain.ClaimInput;

/**
 * Accepts anything the old database could hold, negative credit lines included. The claims service is
 * where new claims get stricter checks.
 */
public record QuoteRequest(
        @NotBlank String dealerCode,
        @NotBlank String modelCode,
        @NotNull @Pattern(regexp = "[SEG]") String claimType,
        @NotNull LocalDate purchaseDate,
        @NotNull LocalDate failureDate,
        @Digits(integer = 7, fraction = 1) BigDecimal meterHours,
        @NotBlank String repairCode,
        @NotNull @Digits(integer = 4, fraction = 2) BigDecimal laborHours,
        @Digits(integer = 6, fraction = 1) BigDecimal travelMiles,
        @NotNull @Size(max = 999) List<@Valid Line> lines) {

    public record Line(int qty, @NotNull @Digits(integer = 8, fraction = 4) BigDecimal unitCost) {}

    ClaimInput toInput() {
        return new ClaimInput(dealerCode, modelCode, claimType, purchaseDate, failureDate, meterHours, repairCode,
                laborHours, travelMiles,
                lines.stream().map(l -> new ClaimInput.PartLine(l.qty(), l.unitCost())).toList());
    }
}
