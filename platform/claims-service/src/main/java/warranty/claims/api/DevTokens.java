package warranty.claims.api;

import io.swagger.v3.oas.annotations.Hidden;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import warranty.commons.LocalTokenSource;
import warranty.commons.TokenSource;

/** Stands in for the Cognito sign-in page on a laptop. Exists only when the local secret is set. */
@Hidden
@RestController
@ConditionalOnProperty("warranty.auth.local-secret")
public class DevTokens {
    private final LocalTokenSource tokens;

    public DevTokens(TokenSource tokens) {
        this.tokens = (LocalTokenSource) tokens;
    }

    @PostMapping("/dev/token")
    public Map<String, String> token(@RequestBody Request r) {
        return Map.of("token", tokens.personToken(r.user(), r.groups()));
    }

    public record Request(String user, List<String> groups) {}
}
