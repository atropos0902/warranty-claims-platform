package warranty.assistant.policy;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import warranty.assistant.policy.ClauseIndex.Scored;

/** Puts the search results in a better order. */
public interface Reranker {
    List<Scored> rerank(String query, List<Scored> found);

    /** Calls a Text Embeddings Inference container running bge-reranker-v2-m3. */
    class Tei implements Reranker {
        private static final Logger log = LoggerFactory.getLogger(Reranker.class);
        private final RestClient http;

        public Tei(RestClient http) {
            this.http = http;
        }

        record Rank(int index, double score) {}

        @Override
        public List<Scored> rerank(String query, List<Scored> found) {
            if (found.isEmpty()) {
                return found;
            }
            try {
                List<Rank> ranks = http.post().uri("/rerank")
                        .body(Map.of("query", query, "truncate", true, "texts",
                                found.stream().map(s -> s.clause().title() + ". " + s.clause().text()).toList()))
                        .retrieve().body(new ParameterizedTypeReference<>() {});
                return ranks.stream().sorted(Comparator.comparingDouble(Rank::score).reversed())
                        .map(r -> new Scored(found.get(r.index()).clause(), r.score())).toList();
            } catch (RestClientException e) {
                log.warn("Reranker unavailable, keeping the search order: {}", e.getMessage());
                return found;
            }
        }
    }
}
