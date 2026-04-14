package warranty.commons;

import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** REST client for calls between services, with a short timeout, the caller's own token and the current trace. */
public final class ServiceClient {
    private ServiceClient() {}

    public static RestClient create(String baseUrl, TokenSource tokens, Duration timeout, ObservationRegistry observations) {
        var factory = new JdkClientHttpRequestFactory(
                java.net.http.HttpClient.newBuilder()
                        .version(java.net.http.HttpClient.Version.HTTP_1_1).connectTimeout(timeout).build());
        factory.setReadTimeout(timeout);
        return RestClient.builder().baseUrl(baseUrl).requestFactory(factory).observationRegistry(observations)
                .requestInterceptor((request, body, next) -> {
                    request.getHeaders().setBearerAuth(tokens.token());
                    return next.execute(request, body);
                }).build();
    }
}
