package warranty.assistant.policy;

import java.io.IOException;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.http.ContentStreamProvider;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.http.SdkHttpRequest;
import software.amazon.awssdk.http.auth.aws.signer.AwsV4HttpSigner;

/** Signs requests for OpenSearch Serverless, whose service name is aoss. */
public record SigV4(AwsCredentialsProvider credentials, String region) implements ClientHttpRequestInterceptor {

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution next) throws IOException {
        var plain = SdkHttpRequest.builder().uri(request.getURI()).method(SdkHttpMethod.fromValue(request.getMethod().name()))
                .putHeader("content-type", "application/json").build();
        var signed = AwsV4HttpSigner.create().sign(r -> r
                .identity(credentials.resolveCredentials())
                .request(plain)
                .payload(ContentStreamProvider.fromByteArray(body))
                .putProperty(AwsV4HttpSigner.SERVICE_SIGNING_NAME, "aoss")
                .putProperty(AwsV4HttpSigner.REGION_NAME, region));
        signed.request().forEachHeader((name, values) -> {
            if (!name.equalsIgnoreCase("host")) {
                request.getHeaders().put(name, values);
            }
        });
        return next.execute(request, body);
    }
}
