package warranty.legacy;

import com.opensymphony.xwork2.ActionSupport;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import org.apache.struts2.interceptor.parameter.StrutsParameter;

public class ViewClaimAction extends ActionSupport {
    private String claimNo;
    private ClaimRow claim;

    @Override
    public String execute() throws Exception {
        try (Connection c = Db.open();
             PreparedStatement ps = c.prepareStatement("SELECT * FROM claims WHERE claim_no = ?")) {
            ps.setString(1, claimNo);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    addActionError("No claim with that number");
                    return ERROR;
                }
                claim = ClaimRow.from(rs);
            }
        }
        return SUCCESS;
    }

    @StrutsParameter
    public void setClaimNo(String claimNo) { this.claimNo = claimNo; }
    public String getClaimNo() { return claimNo; }
    public ClaimRow getClaim() { return claim; }
}
