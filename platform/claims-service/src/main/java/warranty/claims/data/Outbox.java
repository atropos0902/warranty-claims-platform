package warranty.claims.data;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Events written in the same transaction as the change they describe. */
@Repository
public class Outbox {
    private final JdbcClient db;
    private final ObjectMapper json;

    public Outbox(JdbcClient db, ObjectMapper json) {
        this.db = db;
        this.json = json;
    }

    public enum Destination { REPLAY, COMPARE }

    public record Waiting(long id, String eventId, Destination destination, String claimNo, String payload) {}

    public void add(Destination to, String claimNo, String groupCode, int version, Object payload) {
        try {
            db.sql("""
                    INSERT INTO outbox (event_id, destination, claim_no, group_code, claim_version, payload)
                    VALUES (:e, :d, :c, :g, :v, :p)""")
                    .param("e", UUID.randomUUID().toString()).param("d", to.name()).param("c", claimNo)
                    .param("g", groupCode).param("v", version).param("p", json.writeValueAsString(payload)).update();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** False when another relay holds the lock. */
    public boolean lockRelay() {
        return db.sql("SELECT name FROM job_lock WHERE name = 'relay' FOR UPDATE SKIP LOCKED")
                .query(String.class).optional().isPresent();
    }

    public List<Waiting> waiting(int limit) {
        return db.sql("SELECT id, event_id, destination, claim_no, payload FROM outbox WHERE NOT published ORDER BY id LIMIT :l")
                .param("l", limit).query(Waiting.class).list();
    }

    public void published(long id) {
        db.sql("UPDATE outbox SET published = TRUE WHERE id = :i").param("i", id).update();
    }
}
