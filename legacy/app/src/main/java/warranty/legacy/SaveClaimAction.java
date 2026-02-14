package warranty.legacy;

import com.opensymphony.xwork2.ActionSupport;
import java.math.BigDecimal;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import org.apache.struts2.interceptor.parameter.StrutsParameter;

/** Files a claim with one part line, then lets the database price it. */
public class SaveClaimAction extends ActionSupport {
    private static final int GROUP_MOVED = 20010;

    private String claimNo;
    private String dealerCode;
    private String modelCode;
    private String serialNo;
    private String claimType;
    private String purchaseDate;
    private String failureDate;
    private String meterHours;
    private String repairCode;
    private String laborHours;
    private String travelMiles;
    private String partNo;
    private String qty;
    private String unitCost;
    private String notes;

    @Override
    public String execute() throws Exception {
        claimNo = "L" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        try (Connection c = Db.open()) {
            try {
                insertClaim(c);
                insertLine(c);
                try (CallableStatement cs = c.prepareCall("{call claim_pricing.price_claim(?)}")) {
                    cs.setString(1, claimNo);
                    cs.execute();
                }
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                if (e.getErrorCode() == GROUP_MOVED) {
                    addActionError("This dealer group files claims on the new platform now.");
                    return INPUT;
                }
                throw e;
            }
        } catch (IllegalArgumentException e) {
            addActionError("Check the dates and numbers.");
            return INPUT;
        }
        return SUCCESS;
    }

    private void insertClaim(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO claims (claim_no, dealer_code, model_code, serial_no, claim_type, purchase_date,"
                + " failure_date, meter_hours, repair_code, labor_hours, travel_miles, notes, line_count)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,1)")) {
            ps.setString(1, claimNo);
            ps.setString(2, dealerCode);
            ps.setString(3, modelCode);
            ps.setString(4, serialNo);
            ps.setString(5, claimType);
            ps.setDate(6, Date.valueOf(purchaseDate));
            ps.setDate(7, Date.valueOf(failureDate));
            ps.setBigDecimal(8, optional(meterHours));
            ps.setString(9, repairCode);
            ps.setBigDecimal(10, new BigDecimal(laborHours));
            ps.setBigDecimal(11, optional(travelMiles));
            ps.setString(12, notes);
            ps.executeUpdate();
        }
    }

    private void insertLine(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO claim_lines VALUES (?,1,?,?,?)")) {
            ps.setString(1, claimNo);
            ps.setString(2, partNo);
            ps.setInt(3, Integer.parseInt(qty));
            ps.setBigDecimal(4, new BigDecimal(unitCost));
            ps.executeUpdate();
        }
    }

    private static BigDecimal optional(String value) {
        return value == null || value.trim().isEmpty() ? null : new BigDecimal(value.trim());
    }

    public String getClaimNo() { return claimNo; }
    public String getDealerCode() { return dealerCode; }
    @StrutsParameter public void setDealerCode(String v) { dealerCode = v; }
    public String getModelCode() { return modelCode; }
    @StrutsParameter public void setModelCode(String v) { modelCode = v; }
    public String getSerialNo() { return serialNo; }
    @StrutsParameter public void setSerialNo(String v) { serialNo = v; }
    public String getClaimType() { return claimType; }
    @StrutsParameter public void setClaimType(String v) { claimType = v; }
    public String getPurchaseDate() { return purchaseDate; }
    @StrutsParameter public void setPurchaseDate(String v) { purchaseDate = v; }
    public String getFailureDate() { return failureDate; }
    @StrutsParameter public void setFailureDate(String v) { failureDate = v; }
    public String getMeterHours() { return meterHours; }
    @StrutsParameter public void setMeterHours(String v) { meterHours = v; }
    public String getRepairCode() { return repairCode; }
    @StrutsParameter public void setRepairCode(String v) { repairCode = v; }
    public String getLaborHours() { return laborHours; }
    @StrutsParameter public void setLaborHours(String v) { laborHours = v; }
    public String getTravelMiles() { return travelMiles; }
    @StrutsParameter public void setTravelMiles(String v) { travelMiles = v; }
    public String getPartNo() { return partNo; }
    @StrutsParameter public void setPartNo(String v) { partNo = v; }
    public String getQty() { return qty; }
    @StrutsParameter public void setQty(String v) { qty = v; }
    public String getUnitCost() { return unitCost; }
    @StrutsParameter public void setUnitCost(String v) { unitCost = v; }
    public String getNotes() { return notes; }
    @StrutsParameter public void setNotes(String v) { notes = v; }
}
