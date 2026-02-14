package warranty.legacy;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;

public class ClaimRow {
    private String claimNo;
    private String dealerCode;
    private String modelCode;
    private String claimType;
    private String status;
    private Date failureDate;
    private String repairCode;
    private BigDecimal laborHours;
    private BigDecimal coveragePct;
    private BigDecimal laborPay;
    private BigDecimal partsPay;
    private BigDecimal travelPay;
    private BigDecimal totalPay;
    private String notes;

    static ClaimRow from(ResultSet rs) throws SQLException {
        ClaimRow r = new ClaimRow();
        r.claimNo = rs.getString("claim_no");
        r.dealerCode = rs.getString("dealer_code");
        r.modelCode = rs.getString("model_code");
        r.claimType = rs.getString("claim_type");
        r.status = rs.getString("status");
        r.failureDate = rs.getDate("failure_date");
        r.repairCode = rs.getString("repair_code");
        r.laborHours = rs.getBigDecimal("labor_hours");
        r.coveragePct = rs.getBigDecimal("coverage_pct");
        r.laborPay = rs.getBigDecimal("labor_pay");
        r.partsPay = rs.getBigDecimal("parts_pay");
        r.travelPay = rs.getBigDecimal("travel_pay");
        r.totalPay = rs.getBigDecimal("total_pay");
        r.notes = rs.getString("notes");
        return r;
    }

    public String getClaimNo() { return claimNo; }
    public String getDealerCode() { return dealerCode; }
    public String getModelCode() { return modelCode; }
    public String getClaimType() { return claimType; }
    public String getStatus() { return status; }
    public Date getFailureDate() { return failureDate; }
    public String getRepairCode() { return repairCode; }
    public BigDecimal getLaborHours() { return laborHours; }
    public BigDecimal getCoveragePct() { return coveragePct; }
    public BigDecimal getLaborPay() { return laborPay; }
    public BigDecimal getPartsPay() { return partsPay; }
    public BigDecimal getTravelPay() { return travelPay; }
    public BigDecimal getTotalPay() { return totalPay; }
    public String getNotes() { return notes; }
}
