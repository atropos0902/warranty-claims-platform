package warranty.claims.flow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import warranty.claims.api.ClaimRequest;
import warranty.claims.api.ClaimView;
import warranty.claims.data.ClaimStore;
import warranty.claims.data.GroupStore;
import warranty.claims.data.GroupStore.Owner;
import warranty.claims.data.Outbox;
import warranty.claims.data.Outbox.Destination;
import warranty.commons.ApiException;

/** Filing, editing and deciding. Pricing is called before the transaction opens, never inside it. */
@Service
public class ClaimFlow {
    private final ClaimStore claims;
    private final GroupStore groups;
    private final Outbox outbox;
    private final PricingGateway pricing;
    private final TransactionTemplate tx;
    private final JdbcClient db;
    private final ObjectMapper json;

    public ClaimFlow(ClaimStore claims, GroupStore groups, Outbox outbox, PricingGateway pricing, TransactionTemplate tx,
                     JdbcClient db, ObjectMapper json) {
        this.claims = claims;
        this.groups = groups;
        this.outbox = outbox;
        this.pricing = pricing;
        this.tx = tx;
        this.db = db;
        this.json = json;
    }

    public record Filed(ClaimView claim, boolean created) {}

    public Filed file(String caller, String key, ClaimRequest r) {
        String hash = hash(r);
        Optional<Filed> earlier = earlier(caller, key, hash);
        if (earlier.isPresent()) {
            return earlier.get();
        }
        var price = pricing.price(r);
        String claimNo = "P" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(Locale.ROOT);
        try {
            return tx.execute(s -> {
                db.sql("INSERT INTO idempotency_key VALUES (:c, :k, :h, :n)")
                        .param("c", caller).param("k", key).param("h", hash).param("n", claimNo).update();
                requirePlatform(groups.groupOf(r.dealerCode()));
                claims.insert(claimNo, r, "SUBMITTED", 1);
                price.ifPresent(p -> claims.price(claimNo, p));
                return new Filed(emit(claimNo), true);
            });
        } catch (DuplicateKeyException e) {
            // The same key was filed at the same moment. The other request has committed by now.
            return earlier(caller, key, hash).orElseThrow(() -> e);
        }
    }

    public ClaimView edit(String claimNo, int version, ClaimRequest r) {
        var price = pricing.price(r);
        return tx.execute(s -> {
            ClaimView now = locked(claimNo);
            if (!now.groupCode().equals(groups.groupOf(r.dealerCode()))) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "GROUP_FIXED",
                        "A claim cannot change dealer group");
            }
            requireVersion(now, version);
            requireOpen(now);
            claims.update(claimNo, r, "SUBMITTED", now.version() + 1);
            price.ifPresent(p -> claims.price(claimNo, p));
            return emit(claimNo);
        });
    }

    public ClaimView decide(String claimNo, int version, String decision, String note, String who) {
        return tx.execute(s -> {
            ClaimView now = locked(claimNo);
            requireVersion(now, version);
            requireOpen(now);
            if (decision.equals("APPROVED") && now.price() == null) {
                throw new ApiException(HttpStatus.CONFLICT, "NOT_PRICED", "This claim has no price yet");
            }
            claims.decide(claimNo, decision, note, who, now.version() + 1);
            return emit(claimNo);
        });
    }

    /** Fills in a price that was missing. The inputs are unchanged but the version moves, since the total is new. */
    public void priceLater(String claimNo) {
        ClaimView seen = claims.find(claimNo).orElseThrow();
        pricing.price(seen.toRequest()).ifPresent(p -> tx.executeWithoutResult(s -> {
            ClaimView now = locked(claimNo);
            if (now.version() != seen.version() || now.price() != null || !now.status().equals("SUBMITTED")) {
                return;
            }
            claims.update(claimNo, now.toRequest(), "SUBMITTED", now.version() + 1);
            claims.price(claimNo, p);
            emit(claimNo);
        }));
    }

    /** Locks the group before the claim. Every writer uses that order. */
    private ClaimView locked(String claimNo) {
        ClaimView seen = claims.find(claimNo).orElseThrow(
                () -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "No such claim or dealer"));
        requirePlatform(seen.groupCode());
        return claims.lock(claimNo).orElseThrow();
    }

    private ClaimView emit(String claimNo) {
        ClaimView saved = claims.find(claimNo).orElseThrow();
        outbox.add(Destination.REPLAY, claimNo, saved.groupCode(), saved.version(), saved);
        return saved;
    }

    private void requirePlatform(String groupCode) {
        Owner owner = groups.ownerForWrite(groupCode);
        if (owner != Owner.PLATFORM) {
            throw new ApiException(HttpStatus.CONFLICT, "GROUP_ON_LEGACY",
                    owner == Owner.LEGACY ? "This dealer group still files claims in the old system"
                            : "This dealer group is being moved. Try again in a few minutes",
                    Map.of("owner", owner.name()));
        }
    }

    private static void requireVersion(ClaimView now, int version) {
        if (now.version() != version) {
            throw new ApiException(HttpStatus.PRECONDITION_FAILED, "STALE",
                    "Someone else changed this claim while it was open", Map.of("current", now));
        }
    }

    private static void requireOpen(ClaimView now) {
        if (!now.status().equals("SUBMITTED")) {
            throw new ApiException(HttpStatus.CONFLICT, "DECIDED", "This claim has already been decided");
        }
    }

    private Optional<Filed> earlier(String caller, String key, String hash) {
        record Key(String requestHash, String claimNo) {}
        return db.sql("SELECT request_hash, claim_no FROM idempotency_key WHERE caller = :c AND idem_key = :k")
                .param("c", caller).param("k", key).query(Key.class).optional().map(found -> {
                    if (!found.requestHash().equals(hash)) {
                        throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "KEY_REUSED",
                                "That idempotency key was used for a different claim", Map.of("claimNo", found.claimNo()));
                    }
                    return new Filed(claims.find(found.claimNo()).orElseThrow(), false);
                });
    }

    private String hash(ClaimRequest r) {
        try {
            byte[] body = json.writeValueAsString(r).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
