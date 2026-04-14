package warranty.claims.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** A claim as a dealer files or edits it. */
public record ClaimRequest(
        @NotBlank @Size(max = 10) String dealerCode,
        @NotBlank @Size(max = 10) String modelCode,
        @NotBlank @Size(max = 30) String serialNo,
        @NotNull @Pattern(regexp = "[SEG]", message = "must be S, E or G") String claimType,
        @NotNull LocalDate purchaseDate,
        @NotNull LocalDate failureDate,
        @DecimalMin("0") @Digits(integer = 6, fraction = 1) BigDecimal meterHours,
        @NotBlank @Size(max = 10) String repairCode,
        @NotNull @DecimalMin("0") @Digits(integer = 3, fraction = 2) BigDecimal laborHours,
        @DecimalMin("0") @Digits(integer = 5, fraction = 1) BigDecimal travelMiles,
        @Size(max = 2000) String notes,
        @NotNull @Size(max = 50) List<@Valid Line> lines) {

    public record Line(@NotBlank @Size(max = 20) String partNo, @Min(1) @Max(9999) int qty,
                       @NotNull @DecimalMin("0") @Digits(integer = 6, fraction = 4) BigDecimal unitCost) {}
}
