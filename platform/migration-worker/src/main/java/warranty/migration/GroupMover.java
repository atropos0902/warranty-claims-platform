package warranty.migration;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import warranty.commons.ApiException;
import warranty.migration.LegacyClaims.Version;

/**
 * Moves a dealer group between the systems. The owner column is the only record of progress, so a move
 * that was cut off can be asked for again and carries on. If the two sides do not agree in time the
 * group goes back to where it was.
 */
@Component
class GroupMover {
    private static final Logger log = LoggerFactory.getLogger(GroupMover.class);
    private final LegacyClaims legacy;
    private final RestClient claims;
    private final RestClient pricing;
    private final RouteSwitch routes;
    private final Duration timeout;

    GroupMover(LegacyClaims legacy, RestClient claimsHttp, RestClient pricingHttp, RouteSwitch routes,
               @Value("${warranty.move-timeout}") Duration timeout) {
        this.legacy = legacy;
        this.claims = claimsHttp;
        this.pricing = pricingHttp;
        this.routes = routes;
        this.timeout = timeout;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record State(String owner, int waitingEvents, int waitingRows, List<Version> claims) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Readiness(int compared, int openDifferences, boolean ready, long versionTotal) {}

    record Result(String groupCode, String owner, int claims, long seconds) {}

    Result toPlatform(String group) {
        Instant start = Instant.now();
        String owner = state(group).owner();
        if (owner.equals("PLATFORM")) {
            // An earlier move got this far and may have stopped before the route changed.
            legacy.allowWrites(group, false);
            routes.toPlatform(group);
            return done(group, start);
        }
        if (owner.equals("RETURNING")) {
            throw conflict("RETURNING", "The group is on its way back to the old system");
        }
        requireReady(group);
        legacy.allowWrites(group, false);
        if (owner.equals("LEGACY")) {
            change(group, "LEGACY", "MOVING");
        }
        boolean moved = false;
        try {
            waitFor(() -> agree(group));
            // A claim filed in the last seconds may have brought a new difference with it.
            waitFor(() -> allCompared(group));
            requireReady(group);
            change(group, "MOVING", "PLATFORM");
            moved = true;
        } finally {
            if (!moved) {
                change(group, "MOVING", "LEGACY");
                legacy.allowWrites(group, true);
            }
        }
        routes.toPlatform(group);
        return done(group, start);
    }

    Result toLegacy(String group) {
        Instant start = Instant.now();
        String owner = state(group).owner();
        if (owner.equals("LEGACY")) {
            legacy.allowWrites(group, true);
            routes.toLegacy(group);
            return done(group, start);
        }
        if (owner.equals("MOVING")) {
            throw conflict("MOVING", "The group is on its way to the platform");
        }
        if (owner.equals("PLATFORM")) {
            change(group, "PLATFORM", "RETURNING");
        }
        boolean returned = false;
        try {
            waitFor(() -> agree(group));
            // Owner first. Once Oracle takes writes again the loader must be ready to copy them.
            change(group, "RETURNING", "LEGACY");
            returned = true;
        } finally {
            if (!returned) {
                change(group, "RETURNING", "PLATFORM");
            }
        }
        legacy.allowWrites(group, true);
        routes.toLegacy(group);
        return done(group, start);
    }

    /** Nothing waiting, and both sides hold the same claims at the same versions. */
    private boolean agree(String group) {
        State s = state(group);
        return s.waitingEvents() == 0 && s.waitingRows() == 0
                && new HashSet<>(s.claims()).equals(new HashSet<>(legacy.versions(group)));
    }

    private boolean allCompared(String group) {
        List<Version> held = legacy.versions(group);
        Readiness r = readiness(group);
        return r.compared() == held.size() && r.versionTotal() == held.stream().mapToLong(Version::version).sum();
    }

    private void requireReady(String group) {
        Readiness r = readiness(group);
        if (!r.ready()) {
            throw conflict("NOT_READY", r.openDifferences() + " unexplained differences in " + r.compared() + " compared claims");
        }
    }

    private void waitFor(BooleanSupplier ready) {
        Instant giveUp = Instant.now().plus(timeout);
        while (!ready.getAsBoolean()) {
            if (Instant.now().isAfter(giveUp)) {
                throw conflict("TIMED_OUT", "The two systems did not agree in time, so the group stays where it was");
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw conflict("INTERRUPTED", "The move was interrupted");
            }
        }
    }

    private State state(String group) {
        return claims.get().uri("/api/v1/dealer-groups/{g}/transfer-state", group).retrieve().body(State.class);
    }

    private Readiness readiness(String group) {
        return pricing.get().uri("/api/v1/pricing/dealer-groups/{g}/readiness", group).retrieve().body(Readiness.class);
    }

    private void change(String group, String from, String to) {
        claims.post().uri("/api/v1/dealer-groups/{g}/owner", group).body(Map.of("from", from, "to", to)).retrieve()
                .toBodilessEntity();
        log.info("Group {} {} -> {}", group, from, to);
    }

    private Result done(String group, Instant start) {
        State s = state(group);
        return new Result(group, s.owner(), s.claims().size(), Duration.between(start, Instant.now()).toSeconds());
    }

    private static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }
}
