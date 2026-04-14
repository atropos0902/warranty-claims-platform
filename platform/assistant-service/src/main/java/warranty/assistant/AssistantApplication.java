package warranty.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import warranty.assistant.draft.ChatModelAdapter;
import warranty.assistant.draft.KeywordModel;
import warranty.assistant.draft.Model;
import warranty.assistant.policy.ClauseIndex;
import warranty.assistant.policy.KeywordClauseIndex;
import warranty.assistant.policy.OpenSearchClauseIndex;
import warranty.assistant.policy.PolicyManual;
import warranty.assistant.policy.Reranker;
import warranty.assistant.policy.SigV4;
import warranty.commons.ServiceClient;
import warranty.commons.TokenSource;

@SpringBootApplication
public class AssistantApplication {
    public static void main(String[] args) {
        SpringApplication.run(AssistantApplication.class, args);
    }

    @Bean
    Model draftModel(ObjectProvider<ChatModel> chat, ObjectMapper json) {
        ChatModel model = chat.getIfAvailable();
        return model == null ? new KeywordModel() : new ChatModelAdapter(model, json);
    }

    @Bean
    ClauseIndex clauseIndex(@Value("${warranty.search-url}") String url, @Value("${spring.ai.bedrock.aws.region}") String region,
                            ObjectProvider<EmbeddingModel> embeddings, PolicyManual manual) {
        EmbeddingModel model = embeddings.getIfAvailable();
        if (url.isBlank() || model == null) {
            return new KeywordClauseIndex(manual);
        }
        var http = RestClient.builder().baseUrl(url).requestFactory(timeout(Duration.ofSeconds(5)))
                .requestInterceptor(new SigV4(DefaultCredentialsProvider.builder().build(), region)).build();
        var index = new OpenSearchClauseIndex(http, model, manual);
        try {
            index.load();
        } catch (RuntimeException e) {
            LoggerFactory.getLogger(AssistantApplication.class).warn("Clause index not loaded, keyword search will be used", e);
        }
        return index;
    }

    @Bean
    Reranker reranker(@Value("${warranty.reranker-url}") String url) {
        if (url.isBlank()) {
            return (query, found) -> found;
        }
        return new Reranker.Tei(RestClient.builder().baseUrl(url).requestFactory(timeout(Duration.ofSeconds(3))).build());
    }

    @Bean
    RestClient claimsHttp(@Value("${warranty.claims-url}") String url, TokenSource tokens, ObservationRegistry observations) {
        return ServiceClient.create(url, tokens, Duration.ofSeconds(3), observations);
    }

    @Bean
    RestClient pricingHttp(@Value("${warranty.pricing-url}") String url, TokenSource tokens, ObservationRegistry observations) {
        return ServiceClient.create(url, tokens, Duration.ofSeconds(3), observations);
    }

    private static JdkClientHttpRequestFactory timeout(Duration d) {
        var factory = new JdkClientHttpRequestFactory(java.net.http.HttpClient.newBuilder()
                .version(java.net.http.HttpClient.Version.HTTP_1_1).connectTimeout(d).build());
        factory.setReadTimeout(d);
        return factory;
    }
}
