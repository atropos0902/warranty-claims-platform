package warranty.commons;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Every service accepts the same tokens. People carry Cognito groups, machines carry scopes. A
 * machine token never has a group, so a rule that asks for a role cannot be met by a service.
 */
@Configuration
@EnableMethodSecurity
public class ApiSecurity {

    @Bean
    SecurityFilterChain api(HttpSecurity http) throws Exception {
        return http
                .csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/actuator/health/**", "/v3/api-docs", "/v3/api-docs.yaml").permitAll()
                        .requestMatchers(HttpMethod.POST, "/dev/token").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(converter())))
                .build();
    }

    static JwtAuthenticationConverter converter() {
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(ApiSecurity::authorities);
        return converter;
    }

    static Collection<GrantedAuthority> authorities(Jwt jwt) {
        List<GrantedAuthority> out = new ArrayList<>();
        List<String> groups = jwt.getClaimAsStringList("cognito:groups");
        if (groups != null) {
            groups.forEach(g -> out.add(new SimpleGrantedAuthority("ROLE_" + g)));
        }
        String scope = jwt.getClaimAsString("scope");
        if (scope != null) {
            for (String s : scope.split(" ")) {
                out.add(new SimpleGrantedAuthority("SCOPE_" + s));
            }
        }
        return out;
    }

    @Bean
    @ConditionalOnProperty("warranty.auth.local-secret")
    JwtDecoder localDecoder(AuthProperties auth) {
        return NimbusJwtDecoder.withSecretKey(key(auth.localSecret())).macAlgorithm(MacAlgorithm.HS256).build();
    }

    @Bean
    @ConditionalOnProperty("warranty.auth.local-secret")
    JwtEncoder localEncoder(AuthProperties auth) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(key(auth.localSecret())));
    }

    @Bean
    @ConditionalOnProperty("warranty.auth.local-secret")
    TokenSource localTokens(JwtEncoder encoder, AuthProperties auth) {
        return new LocalTokenSource(encoder, auth);
    }

    @Bean
    @ConditionalOnProperty("warranty.auth.token-url")
    TokenSource cognitoTokens(AuthProperties auth) {
        return new CognitoTokenSource(auth);
    }

    private static SecretKey key(String secret) {
        return new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }
}
