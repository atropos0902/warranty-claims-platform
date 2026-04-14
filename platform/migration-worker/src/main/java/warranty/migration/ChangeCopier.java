package warranty.migration;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;
import warranty.migration.LegacyClaims.Header;
import warranty.migration.MigrationApplication.Database;

/** Does on a laptop what the DMS task does in AWS: full load, then changes, from Oracle into staging. */
@Component
@ConditionalOnProperty("warranty.copier.enabled")
class ChangeCopier {
    private static final Logger log = LoggerFactory.getLogger(ChangeCopier.class);
    private final LegacyClaims legacy;
    private final Database staging;

    ChangeCopier(LegacyClaims legacy, Database staging) {
        this.legacy = legacy;
        this.staging = staging;
    }

    @Scheduled(fixedDelay = 500)
    void run() {
        try {
            while (copy() > 0) {
                // keep going until the feed is empty
            }
        } catch (DataAccessException | TransactionException e) {
            log.warn("Copy stopped, will retry: {}", e.getMessage());
        }
    }

    private int copy() {
        long mark = staging.sql().sql("SELECT seq FROM staging.copier_mark WHERE id = 1").query(Long.class).single();
        var copy = legacy.changesAfter(mark, 500);
        if (copy.changes().isEmpty()) {
            return 0;
        }
        staging.tx().executeWithoutResult(s -> {
            for (int i = 0; i < copy.headers().size(); i++) {
                Header h = copy.headers().get(i);
                staging.sql().sql("DELETE FROM staging.claim_lines WHERE claim_no = :n").param("n", h.claimNo()).update();
                for (var l : copy.lines().get(i)) {
                    staging.sql().sql("INSERT INTO staging.claim_lines VALUES (:n, :i, :p, :q, :u)").param("n", h.claimNo())
                            .param("i", l.lineNo()).param("p", l.partNo()).param("q", l.qty()).param("u", l.unitCost()).update();
                }
                header(h);
            }
            staging.sql().sql("UPDATE staging.copier_mark SET seq = :s WHERE id = 1")
                    .param("s", copy.changes().getLast().seq()).update();
        });
        return copy.changes().size();
    }

    private void header(Header h) {
        staging.sql().sql("""
                INSERT INTO staging.claims VALUES (:n, :dealer, :model, :serial, :type, :purchase, :failure, :meter, :repair,
                  :labor, :miles, :notes, :status, :lines, :version, :pct, :lp, :pp, :tp, :total) AS n
                ON DUPLICATE KEY UPDATE dealer_code = n.dealer_code, model_code = n.model_code, serial_no = n.serial_no,
                  claim_type = n.claim_type, purchase_date = n.purchase_date, failure_date = n.failure_date,
                  meter_hours = n.meter_hours, repair_code = n.repair_code, labor_hours = n.labor_hours,
                  travel_miles = n.travel_miles, notes = n.notes, status = n.status, line_count = n.line_count,
                  version_no = n.version_no, coverage_pct = n.coverage_pct, labor_pay = n.labor_pay,
                  parts_pay = n.parts_pay, travel_pay = n.travel_pay, total_pay = n.total_pay""")
                .param("n", h.claimNo()).param("dealer", h.dealerCode()).param("model", h.modelCode())
                .param("serial", h.serialNo()).param("type", h.claimType()).param("purchase", h.purchaseDate())
                .param("failure", h.failureDate()).param("meter", h.meterHours()).param("repair", h.repairCode())
                .param("labor", h.laborHours()).param("miles", h.travelMiles()).param("notes", h.notes())
                .param("status", h.status()).param("lines", h.lineCount()).param("version", h.versionNo())
                .param("pct", h.coveragePct()).param("lp", h.laborPay()).param("pp", h.partsPay())
                .param("tp", h.travelPay()).param("total", h.totalPay()).update();
    }
}
