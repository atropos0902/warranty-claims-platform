package warranty.commons;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

/** Signs tokens with the shared local secret. Never active when that secret is absent. */
public class LocalTokenSource implements TokenSource {
    private static final Duration LIFETIME = Duration.ofHours(8);
    private final JwtEncoder encoder;
    private final AuthProperties auth;

    public LocalTokenSource(JwtEncoder encoder, AuthProperties auth) {
        this.encoder = encoder;
        this.auth = auth;
    }

    @Override
    public String token() {
        return sign(base(auth.clientId() == null ? "service" : auth.clientId())
                .claim("scope", auth.scopes() == null ? "" : auth.scopes()).build());
    }

    public String personToken(String user, List<String> groups) {
        return sign(base(user).claim("cognito:groups", groups).build());
    }

    private static JwtClaimsSet.Builder base(String subject) {
        Instant now = Instant.now();
        return JwtClaimsSet.builder().subject(subject).issuedAt(now).expiresAt(now.plus(LIFETIME));
    }

    private String sign(JwtClaimsSet claims) {
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }
}
