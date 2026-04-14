package warranty.claims.data;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import warranty.commons.ApiException;

@Repository
public class GroupStore {
    private final JdbcClient db;

    public GroupStore(JdbcClient db) {
        this.db = db;
    }

    public enum Owner { LEGACY, MOVING, PLATFORM, RETURNING }

    public record Group(String groupCode, String groupName, Owner owner) {}

    public record ClaimVersion(String claimNo, int version, String status) {}

    public record TransferState(Owner owner, int waitingEvents, int waitingRows, List<ClaimVersion> claims) {}

    public List<Group> all() {
        return db.sql("SELECT group_code, group_name, owner FROM dealer_group ORDER BY group_code")
                .query(Group.class).list();
    }

    public record Dealer(String dealerCode, String dealerName, String groupCode) {}

    public List<Dealer> dealers(String groupLimit) {
        return db.sql("SELECT dealer_code, dealer_name, group_code FROM dealer WHERE :g IS NULL OR group_code = :g ORDER BY dealer_name")
                .param("g", groupLimit).query(Dealer.class).list();
    }

    public String groupOf(String dealerCode) {
        return db.sql("SELECT group_code FROM dealer WHERE dealer_code = :d").param("d", dealerCode)
                .query(String.class).optional()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "No such claim or dealer"));
    }

    /**
     * Takes a shared lock on the group until the transaction ends, so the owner cannot change while a
     * write is in flight.
     */
    public Owner ownerForWrite(String groupCode) {
        return Owner.valueOf(db.sql("SELECT owner FROM dealer_group WHERE group_code = :g FOR SHARE")
                .param("g", groupCode).query(String.class).single());
    }

    @Transactional
    public boolean changeOwner(String groupCode, Owner from, Owner to) {
        return db.sql("UPDATE dealer_group SET owner = :t WHERE group_code = :g AND owner = :f")
                .param("t", to.name()).param("f", from.name()).param("g", groupCode).update() == 1;
    }

    /** What the mover checks before it hands a group over: nothing waiting, and every claim at a known version. */
    @Transactional(readOnly = true)
    public TransferState transferState(String groupCode) {
        Owner owner = Owner.valueOf(db.sql("SELECT owner FROM dealer_group WHERE group_code = :g")
                .param("g", groupCode).query(String.class).single());
        int events = db.sql("SELECT COUNT(*) FROM outbox WHERE NOT published AND destination = 'REPLAY' AND group_code = :g")
                .param("g", groupCode).query(Integer.class).single();
        int rows = db.sql("""
                SELECT COUNT(*) FROM staging.change_queue q JOIN staging.claims s ON s.claim_no = q.claim_no
                  JOIN dealer d ON d.dealer_code = s.dealer_code WHERE d.group_code = :g""")
                .param("g", groupCode).query(Integer.class).single();
        return new TransferState(owner, events, rows, db.sql("""
                SELECT c.claim_no, c.version_no AS version, c.status FROM claim c
                  JOIN dealer d ON d.dealer_code = c.dealer_code WHERE d.group_code = :g ORDER BY c.claim_no""")
                .param("g", groupCode).query(ClaimVersion.class).list());
    }
}
