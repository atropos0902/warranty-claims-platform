package warranty.claims;

import io.micrometer.observation.ObservationRegistry;
import java.net.URI;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.client.RestClient;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import warranty.commons.ServiceClient;
import warranty.commons.TokenSource;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ClaimsApplication {
    public static void main(String[] args) {
        SpringApplication.run(ClaimsApplication.class, args);
    }

    @Configuration
    @EnableScheduling
    @ConditionalOnProperty("warranty.jobs.enabled")
    static class Jobs {
        /** One thread per job, so a slow one does not hold up the relay. */
        @Bean
        ThreadPoolTaskScheduler taskScheduler() {
            var scheduler = new ThreadPoolTaskScheduler();
            scheduler.setPoolSize(3);
            scheduler.setThreadNamePrefix("jobs-");
            return scheduler;
        }
    }

    @Bean
    RestClient pricingHttp(@Value("${warranty.pricing-url}") String url, TokenSource tokens, ObservationRegistry observations) {
        return ServiceClient.create(url, tokens, Duration.ofSeconds(2), observations);
    }

    @Bean
    SqsClient sqs(QueueProperties queues) {
        var builder = SqsClient.builder().httpClientBuilder(UrlConnectionHttpClient.builder());
        if (queues.endpoint() != null && !queues.endpoint().isBlank()) {
            builder.endpointOverride(URI.create(queues.endpoint())).region(Region.US_EAST_1)
                    .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("local", "local")));
        }
        return builder.build();
    }
}
