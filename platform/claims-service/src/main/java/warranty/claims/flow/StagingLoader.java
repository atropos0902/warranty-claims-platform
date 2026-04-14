package warranty.claims.flow;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import warranty.claims.api.ClaimRequest;
import warranty.claims.api.ClaimView;
import warranty.claims.data.ClaimStore;
import warranty.claims.data.GroupStore;
import warranty.claims.data.GroupStore.Owner;
import warranty.claims.data.Outbox;

/**
 * Copies rows DMS brought over into the claims tables, for groups the old system still owns. A group
 * that has moved is skipped: its rows in staging are echoes of our own replay, and some are older than
 * what is here.
 */
@Component
public class StagingLoader {
    private final JdbcClient db;
    private final ClaimStore claims;
    private final GroupStore groups;
    private final Outbox outbox;
    private final TransactionTemplate tx;

    public StagingLoader(JdbcClient db, ClaimStore claims, GroupStore groups, Outbox outbox, TransactionTemplate tx) {
        this.db = db;
        this.claims = claims;
        this.groups = groups;
        this.outbox = outbox;
        this.tx = tx;
    }

    record Queued(long id, String claimNo) {}

    @Scheduled(fixedDelay = 500)
    public void run() {
        while (Boolean.TRUE.equals(tx.execute(s -> batch()))) {
            // keep going until the queue is empty
        }
    }

    private boolean batch() {
        List<Queued> queued = db.sql("SELECT id, claim_no FROM staging.change_queue ORDER BY id LIMIT 200 FOR UPDATE SKIP LOCKED")
                .query(Queued.class).list();
        if (queued.isEmpty()) {
            return false;
        }
        Set<String> numbers = new LinkedHashSet<>();
        queued.forEach(q -> numbers.add(q.claimNo()));
        numbers.forEach(this::load);
        db.sql("DELETE FROM staging.change_queue WHERE id IN (:ids)").param("ids", queued.stream().map(Queued::id).toList())
                .update();
        return true;
    }

    private void load(String claimNo) {
        record Head(String dealerCode, String modelCode, String serialNo, String claimType, java.time.LocalDate purchaseDate,
                    java.time.LocalDate failureDate, java.math.BigDecimal meterHours, String repairCode,
                    java.math.BigDecimal laborHours, java.math.BigDecimal travelMiles, String notes, String status,
                    int lineCount, int versionNo, java.math.BigDecimal coveragePct, java.math.BigDecimal laborPay,
                    java.math.BigDecimal partsPay, java.math.BigDecimal travelPay, java.math.BigDecimal totalPay) {}
        var head = db.sql("SELECT * FROM staging.claims WHERE claim_no = :n").param("n", claimNo).query(Head.class).optional();
        // A header without all its lines, or without a price, is half a claim. The rest queues it again.
        if (head.isEmpty() || head.get().totalPay() == null) {
            return;
        }
        Head h = head.get();
        var lines = db.sql("SELECT part_no, qty, unit_cost FROM staging.claim_lines WHERE claim_no = :n ORDER BY line_no")
                .param("n", claimNo).query(ClaimRequest.Line.class).list();
        if (lines.size() != h.lineCount()) {
            return;
        }
        String group = groups.groupOf(h.dealerCode());
        Owner owner = groups.ownerForWrite(group);
        if (owner == Owner.PLATFORM || owner == Owner.RETURNING) {
            return;
        }
        var request = new ClaimRequest(h.dealerCode(), h.modelCode(), h.serialNo(), h.claimType(), h.purchaseDate(),
                h.failureDate(), h.meterHours(), h.repairCode(), h.laborHours(), h.travelMiles(), h.notes(), lines);
        var existing = claims.lock(claimNo);
        if (existing.isEmpty()) {
            claims.insert(claimNo, request, h.status(), h.versionNo());
        } else if (existing.get().version() > h.versionNo()) {
            return;
        } else {
            claims.update(claimNo, request, h.status(), h.versionNo());
        }
        var price = new ClaimView.Price(h.coveragePct(), h.laborPay(), h.partsPay(), h.travelPay(), h.totalPay(), List.of());
        claims.price(claimNo, price);
        outbox.add(Outbox.Destination.COMPARE, claimNo, group, h.versionNo(), Map.of(
                "claimNo", claimNo, "groupCode", group, "version", h.versionNo(),
                "claim", PricingGateway.body(request), "legacy", price));
    }
}
