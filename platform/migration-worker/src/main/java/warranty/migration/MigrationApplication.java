package warranty.migration;

import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.observation.ObservationRegistry;
import java.net.URI;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client;
import software.amazon.awssdk.services.sqs.SqsClient;
import warranty.commons.ServiceClient;
import warranty.commons.TokenSource;

@SpringBootApplication(exclude = DataSourceAutoConfiguration.class)
@ConfigurationPropertiesScan
@EnableScheduling
public class MigrationApplication {
    public static void main(String[] args) {
        SpringApplication.run(MigrationApplication.class, args);
    }

    /** Oracle, always. Every session it hands out is marked as replay, which the write gate lets through. */
    @Bean
    Database legacy(@Value("${warranty.legacy.url}") String url, @Value("${warranty.legacy.user}") String user,
                    @Value("${warranty.legacy.password}") String password) {
        var ds = pool(url, user, password);
        ds.setConnectionInitSql("BEGIN DBMS_SESSION.SET_IDENTIFIER('replay'); END;");
        return Database.of(ds);
    }

    /** MySQL staging. Only the local copier writes here. */
    @Bean
    @ConditionalOnProperty("warranty.copier.enabled")
    Database staging(@Value("${warranty.staging.url}") String url, @Value("${warranty.staging.user}") String user,
                     @Value("${warranty.staging.password}") String password) {
        return Database.of(pool(url, user, password));
    }

    private static HikariDataSource pool(String url, String user, String password) {
        var ds = new HikariDataSource();
        ds.setJdbcUrl(url);
        ds.setUsername(user);
        ds.setPassword(password);
        ds.setMaximumPoolSize(4);
        ds.setInitializationFailTimeout(-1);
        return ds;
    }

    record Database(JdbcClient sql, TransactionTemplate tx) {
        static Database of(HikariDataSource ds) {
            return new Database(JdbcClient.create(ds), new TransactionTemplate(new JdbcTransactionManager(ds)));
        }
    }

    @Bean
    RestClient claimsHttp(@Value("${warranty.claims-url}") String url, TokenSource tokens, ObservationRegistry observations) {
        return ServiceClient.create(url, tokens, Duration.ofSeconds(10), observations);
    }

    @Bean
    RestClient pricingHttp(@Value("${warranty.pricing-url}") String url, TokenSource tokens, ObservationRegistry observations) {
        return ServiceClient.create(url, tokens, Duration.ofSeconds(10), observations);
    }

    @Bean
    SqsClient sqs(QueueProperties queues) {
        var builder = SqsClient.builder().httpClientBuilder(UrlConnectionHttpClient.builder()
                .socketTimeout(Duration.ofSeconds(30)));
        if (queues.endpoint() != null && !queues.endpoint().isBlank()) {
            builder.endpointOverride(URI.create(queues.endpoint())).region(Region.US_EAST_1)
                    .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("local", "local")));
        }
        return builder.build();
    }

    @Bean
    RouteSwitch routes(RouteProperties routes) {
        if (routes.listenerRules() == null || routes.listenerRules().isEmpty()) {
            return new RouteSwitch.LogOnly();
        }
        return new RouteSwitch.Alb(ElasticLoadBalancingV2Client.builder()
                .httpClientBuilder(UrlConnectionHttpClient.builder()).build(), routes);
    }
}
