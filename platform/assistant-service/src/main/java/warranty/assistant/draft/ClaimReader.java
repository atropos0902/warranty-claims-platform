package warranty.assistant.draft;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/** Reads other services with a token that carries read scopes and nothing else. */
@Component
public class ClaimReader {
    private final RestClient claims;
    private final RestClient pricing;

    public ClaimReader(RestClient claimsHttp, RestClient pricingHttp) {
        this.claims = claimsHttp;
        this.pricing = pricingHttp;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ClaimData(String claimNo, String groupCode, String modelCode, String serialNo, String claimType,
                            String purchaseDate, String failureDate, String meterHours, String repairCode,
                            String laborHours, String notes, String status, int version, List<Part> lines) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Part(String partNo, int qty) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Repair(String claimNo, String groupCode, String repairCode, String status) {}

    public ClaimData claim(String claimNo) {
        try {
            return claims.get().uri("/api/v1/claims/{n}", claimNo).retrieve().body(ClaimData.class);
        } catch (HttpClientErrorException.NotFound e) {
            return null;
        }
    }

    public List<Repair> repairs(String serialNo) {
        return claims.get().uri("/api/v1/claims?serialNo={s}", serialNo).retrieve()
                .body(new ParameterizedTypeReference<>() {});
    }

    public JsonNode terms(String modelCode) {
        return pricing.get().uri("/api/v1/pricing/terms/{m}", modelCode).retrieve().body(JsonNode.class);
    }
}
