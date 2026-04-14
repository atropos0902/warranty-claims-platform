package warranty.claims;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("warranty.queues")
public record QueueProperties(String endpoint, String replayUrl, String compareUrl) {}
