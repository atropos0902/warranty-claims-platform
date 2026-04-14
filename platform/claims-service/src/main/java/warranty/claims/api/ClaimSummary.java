package warranty.claims.api;

import java.math.BigDecimal;

public record ClaimSummary(String claimNo, String dealerCode, String dealerName, String groupCode, String modelCode,
                           String serialNo, String repairCode, String status, BigDecimal totalPay) {}
