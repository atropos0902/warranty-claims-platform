package warranty.commons;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Local runs set {@code localSecret}. In AWS the issuer comes from the resource server settings and
 * services fetch their own tokens from {@code tokenUrl} with their client id and secret.
 */
@ConfigurationProperties("warranty.auth")
public record AuthProperties(String localSecret, String tokenUrl, String clientId, String clientSecret, String scopes) {}
