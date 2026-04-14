package warranty.claims.flow;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import warranty.claims.api.ClaimRequest;
import warranty.claims.api.ClaimView;
import warranty.commons.ApiException;

@Component
public class PricingGateway {
    private static final Logger log = LoggerFactory.getLogger(PricingGateway.class);
    private final RestClient http;

    public PricingGateway(RestClient pricingHttp) {
        this.http = pricingHttp;
    }

    /** Empty when pricing is down. The claim is kept and priced later. */
    @CircuitBreaker(name = "pricing", fallbackMethod = "unavailable")
    @Retry(name = "pricing")
    public Optional<ClaimView.Price> price(ClaimRequest r) {
        return Optional.ofNullable(http.post().uri("/api/v1/pricing/quotes").body(body(r)).retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, (req, res) -> {
                    if (res.getStatusCode().value() == 422) {
                        throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "NO_TERMS",
                                "No warranty terms for that dealer, model, claim type and repair code");
                    }
                    throw new IllegalStateException("Pricing refused the request: " + res.getStatusCode());
                })
                .body(ClaimView.Price.class));
    }

    @SuppressWarnings("unused")
    private Optional<ClaimView.Price> unavailable(ClaimRequest r, Throwable cause) {
        if (cause instanceof ApiException known) {
            throw known;
        }
        log.warn("Pricing unavailable, claim stays unpriced: {}", cause.toString());
        return Optional.empty();
    }

    static Map<String, Object> body(ClaimRequest r) {
        Map<String, Object> m = new java.util.HashMap<>();
        m.put("dealerCode", r.dealerCode());
        m.put("modelCode", r.modelCode());
        m.put("claimType", r.claimType());
        m.put("purchaseDate", r.purchaseDate().toString());
        m.put("failureDate", r.failureDate().toString());
        m.put("meterHours", r.meterHours());
        m.put("repairCode", r.repairCode());
        m.put("laborHours", r.laborHours());
        m.put("travelMiles", r.travelMiles());
        m.put("lines", r.lines().stream().map(l -> Map.of("qty", l.qty(), "unitCost", l.unitCost())).toList());
        return m;
    }
}
