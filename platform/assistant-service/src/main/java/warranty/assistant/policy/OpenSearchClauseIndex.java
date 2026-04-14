package warranty.assistant.policy;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * Vector search in OpenSearch Serverless. The index name carries a hash of the manual, so a changed
 * manual gets a fresh index. Vector collections there refuse document ids chosen by the client, so a
 * loaded index is recognised by its name and its document count.
 */
public class OpenSearchClauseIndex implements ClauseIndex {
    private final RestClient http;
    private final EmbeddingModel embeddings;
    private final PolicyManual manual;
    private final String index;

    public OpenSearchClauseIndex(RestClient http, EmbeddingModel embeddings, PolicyManual manual) {
        this.http = http;
        this.embeddings = embeddings;
        this.manual = manual;
        this.index = "clauses-" + hash(manual);
    }

    /** Safe to call at every start. */
    public void load() {
        if (exists()) {
            return;
        }
        http.put().uri(URI.create("/" + index).toString()).body(Map.of(
                "settings", Map.of("index.knn", true),
                "mappings", Map.of("properties", Map.of(
                        "id", Map.of("type", "keyword"),
                        "vector", Map.of("type", "knn_vector", "dimension", embeddings.dimensions(),
                                "method", Map.of("name", "hnsw", "engine", "faiss", "space_type", "l2"))))))
                .retrieve().toBodilessEntity();
        for (Clause c : manual.clauses()) {
            http.post().uri("/" + index + "/_doc").body(Map.of("id", c.id(), "vector", embeddings.embed(c.title() + ". " + c.text())))
                    .retrieve().toBodilessEntity();
        }
    }

    @Override
    public List<Scored> search(String query, int limit) {
        JsonNode body = http.post().uri("/" + index + "/_search").body(Map.of(
                "size", limit, "_source", List.of("id"),
                "query", Map.of("knn", Map.of("vector", Map.of("vector", embeddings.embed(query), "k", limit)))))
                .retrieve().body(JsonNode.class);
        List<Scored> out = new ArrayList<>();
        for (JsonNode hit : body.path("hits").path("hits")) {
            Clause clause = manual.get(hit.path("_source").path("id").asText());
            // Two tasks starting together can both load the index, so the same clause may come back twice.
            if (clause != null && out.stream().noneMatch(s -> s.clause().id().equals(clause.id()))) {
                out.add(new Scored(clause, hit.path("_score").asDouble()));
            }
        }
        return out;
    }

    private boolean exists() {
        try {
            JsonNode count = http.post().uri("/" + index + "/_count").body(Map.of()).retrieve().body(JsonNode.class);
            return count.path("count").asInt() >= manual.clauses().size();
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                return false;
            }
            throw e;
        }
    }

    private static String hash(PolicyManual manual) {
        try {
            var sha = MessageDigest.getInstance("SHA-256");
            manual.clauses().forEach(c -> sha.update((c.id() + c.title() + c.text()).getBytes(StandardCharsets.UTF_8)));
            return HexFormat.of().formatHex(sha.digest()).substring(0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
