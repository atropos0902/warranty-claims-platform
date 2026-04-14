package warranty.commons;

import java.time.Instant;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

/** Client credentials grant, cached until a minute before it expires. */
public class CognitoTokenSource implements TokenSource {
    private final AuthProperties auth;
    private final RestClient http = RestClient.create();
    private String cached;
    private Instant renewAt = Instant.EPOCH;

    public CognitoTokenSource(AuthProperties auth) {
        this.auth = auth;
    }

    @Override
    public synchronized String token() {
        if (Instant.now().isBefore(renewAt)) {
            return cached;
        }
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "client_credentials");
        form.add("scope", auth.scopes());
        Map<?, ?> body = http.post().uri(auth.tokenUrl())
                .headers(h -> h.setBasicAuth(auth.clientId(), auth.clientSecret()))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form).retrieve().body(Map.class);
        if (body == null) {
            throw new IllegalStateException("Token endpoint sent no body");
        }
        cached = (String) body.get("access_token");
        renewAt = Instant.now().plusSeconds(((Number) body.get("expires_in")).longValue() - 60);
        return cached;
    }
}
