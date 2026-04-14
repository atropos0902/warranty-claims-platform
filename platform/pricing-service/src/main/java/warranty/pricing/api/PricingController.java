package warranty.pricing.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.security.Principal;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import warranty.commons.ApiException;
import warranty.pricing.data.Comparisons;
import warranty.pricing.data.Comparisons.Difference;
import warranty.pricing.data.Comparisons.LegacyPrice;
import warranty.pricing.data.Comparisons.Readiness;
import warranty.pricing.data.PricingData;
import warranty.pricing.domain.ClaimInput;
import warranty.pricing.domain.PricingEngine;
import warranty.pricing.domain.Quote;

@RestController
@RequestMapping("/api/v1/pricing")
public class PricingController {
    private final PricingData data;
    private final Comparisons comparisons;
    private final ObjectMapper json;

    public PricingController(PricingData data, Comparisons comparisons, ObjectMapper json) {
        this.data = data;
        this.comparisons = comparisons;
        this.json = json;
    }

    @PostMapping("/quotes")
    @PreAuthorize("hasAnyRole('DEALER','ADJUSTER') or hasAuthority('SCOPE_pricing/quote')")
    public Quote quote(@Valid @RequestBody QuoteRequest request) {
        return price(request.toInput());
    }

    @GetMapping("/reference")
    public PricingData.Reference reference() {
        return data.reference();
    }

    @GetMapping("/terms/{model}")
    @PreAuthorize("hasAnyRole('DEALER','ADJUSTER') or hasAuthority('SCOPE_pricing/quote')")
    public List<PricingData.WarrantyTerm> terms(@PathVariable String model) {
        return data.termsForModel(model);
    }

    @PostMapping("/comparisons")
    @PreAuthorize("hasAuthority('SCOPE_pricing/compare')")
    public CompareResult compare(@Valid @RequestBody CompareRequest r) {
        Quote quote = price(r.claim().toInput());
        String split = splitBy(r.legacy(), quote);
        comparisons.save(r.claimNo(), r.groupCode(), r.version(), r.legacy(), quote, split, write(r.claim()));
        return new CompareResult(split == null, split, quote.totalPay());
    }

    @GetMapping("/differences")
    @PreAuthorize("hasAnyRole('FINANCE','OPS')")
    public List<Difference> differences(@RequestParam(required = false) String groupCode) {
        return comparisons.open(groupCode);
    }

    @PostMapping("/differences/{claimNo}/explanation")
    @PreAuthorize("hasRole('FINANCE')")
    public void explain(@PathVariable String claimNo, @Valid @RequestBody Explanation e, Principal who) {
        if (comparisons.explain(claimNo, e.version(), e.reason(), who.getName()) == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "STALE_DIFFERENCE",
                    "That difference is gone or the claim has been priced again");
        }
    }

    @GetMapping("/dealer-groups/{code}/readiness")
    @PreAuthorize("hasAnyRole('FINANCE','OPS') or hasAuthority('SCOPE_pricing/compare')")
    public Readiness readiness(@PathVariable String code) {
        return comparisons.readiness(code);
    }

    private Quote price(ClaimInput input) {
        var terms = data.terms(input).orElseThrow(() -> new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                "NO_TERMS", "No warranty terms for that dealer, model, claim type and repair code"));
        return PricingEngine.price(input, terms, data.flags());
    }

    /** Names the parts of the price that disagree, or null when the two systems agree. */
    static String splitBy(LegacyPrice legacy, Quote quote) {
        List<String> parts = new ArrayList<>();
        add(parts, "coverage", legacy.coveragePct(), quote.coveragePct());
        add(parts, "labor", legacy.laborPay(), quote.laborPay());
        add(parts, "parts", legacy.partsPay(), quote.partsPay());
        add(parts, "travel", legacy.travelPay(), quote.travelPay());
        add(parts, "total", legacy.totalPay(), quote.totalPay());
        return parts.isEmpty() ? null : String.join(",", parts);
    }

    private static void add(List<String> parts, String name, BigDecimal a, BigDecimal b) {
        if (a == null || a.compareTo(b) != 0) {
            parts.add(name);
        }
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    public record CompareRequest(@NotBlank String claimNo, @NotBlank String groupCode, int version,
                                 @Valid @NotNull QuoteRequest claim, @NotNull LegacyPrice legacy) {}

    public record CompareResult(boolean match, String splitBy, BigDecimal platformTotal) {}

    public record Explanation(int version, @NotBlank @Size(max = 500) String reason) {}
}
