package warranty.legacy;

import com.opensymphony.xwork2.ActionSupport;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

public class ListClaimsAction extends ActionSupport {
    private final List<ClaimRow> claims = new ArrayList<>();

    @Override
    public String execute() throws Exception {
        try (Connection c = Db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT * FROM claims ORDER BY claim_no DESC FETCH FIRST 100 ROWS ONLY");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                claims.add(ClaimRow.from(rs));
            }
        }
        return SUCCESS;
    }

    public List<ClaimRow> getClaims() { return claims; }
}
