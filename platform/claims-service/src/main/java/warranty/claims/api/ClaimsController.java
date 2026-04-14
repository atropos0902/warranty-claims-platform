package warranty.claims.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import warranty.claims.data.ClaimStore;
import warranty.claims.data.GroupStore;
import warranty.claims.flow.ClaimFlow;
import warranty.commons.ApiException;

@RestController
@RequestMapping("/api/v1/claims")
public class ClaimsController {
    private final ClaimStore claims;
    private final GroupStore groups;
    private final ClaimFlow flow;

    public ClaimsController(ClaimStore claims, GroupStore groups, ClaimFlow flow) {
        this.claims = claims;
        this.groups = groups;
        this.flow = flow;
    }

    /** Filing twice with the same key returns the first claim. */
    @PostMapping
    @PreAuthorize("hasRole('DEALER')")
    public ResponseEntity<ClaimView> file(@RequestHeader("Idempotency-Key") @Size(min = 8, max = 80) String key,
                                          @Valid @RequestBody ClaimRequest request, Authentication who) {
        Access.check(who, groups.groupOf(request.dealerCode()));
        var filed = flow.file(who.getName(), key, request);
        var builder = filed.created()
                ? ResponseEntity.created(URI.create("/api/v1/claims/" + filed.claim().claimNo()))
                : ResponseEntity.ok();
        return builder.eTag(tag(filed.claim())).body(filed.claim());
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('DEALER','ADJUSTER','FINANCE','OPS') or hasAuthority('SCOPE_claims/read')")
    public List<ClaimSummary> list(@RequestParam(required = false) String status,
                                   @RequestParam(required = false) String serialNo,
                                   @RequestParam(required = false) String after, Authentication who) {
        return claims.list(Access.limit(who), status, serialNo, after);
    }

    @GetMapping("/{claimNo}")
    @PreAuthorize("hasAnyRole('DEALER','ADJUSTER','FINANCE','OPS') or hasAuthority('SCOPE_claims/read')")
    public ResponseEntity<ClaimView> get(@PathVariable String claimNo, Authentication who) {
        ClaimView claim = visible(claimNo, who);
        return ResponseEntity.ok().eTag(tag(claim)).body(claim);
    }

    /** A stale If-Match is refused with the current claim in the body, so the screen can show both. */
    @PutMapping("/{claimNo}")
    @PreAuthorize("hasAnyRole('DEALER','ADJUSTER')")
    public ResponseEntity<ClaimView> edit(@PathVariable String claimNo,
                                          @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                          @Valid @RequestBody ClaimRequest request, Authentication who) {
        visible(claimNo, who);
        Access.check(who, groups.groupOf(request.dealerCode()));
        ClaimView saved = flow.edit(claimNo, version(ifMatch), request);
        return ResponseEntity.ok().eTag(tag(saved)).body(saved);
    }

    /** People only. No service account carries the ADJUSTER group. */
    @PostMapping("/{claimNo}/decision")
    @PreAuthorize("hasRole('ADJUSTER')")
    public ResponseEntity<ClaimView> decide(@PathVariable String claimNo,
                                            @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                            @Valid @RequestBody Decision decision, Authentication who) {
        ClaimView saved = flow.decide(claimNo, version(ifMatch), decision.decision(), decision.note(), who.getName());
        return ResponseEntity.ok().eTag(tag(saved)).body(saved);
    }

    private ClaimView visible(String claimNo, Authentication who) {
        ClaimView claim = claims.find(claimNo).orElseThrow(
                () -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "No such claim or dealer"));
        Access.check(who, claim.groupCode());
        return claim;
    }

    private static String tag(ClaimView claim) {
        return "\"" + claim.version() + "\"";
    }

    private static int version(String ifMatch) {
        if (ifMatch == null) {
            throw new ApiException(HttpStatus.PRECONDITION_REQUIRED, "IF_MATCH_REQUIRED",
                    "Send the version you edited in If-Match");
        }
        try {
            return Integer.parseInt(ifMatch.replace("W/", "").replace("\"", ""));
        } catch (NumberFormatException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "BAD_IF_MATCH", "If-Match is not a claim version");
        }
    }

    public record Decision(@NotNull @Pattern(regexp = "APPROVED|DENIED") String decision, @Size(max = 500) String note) {}
}
