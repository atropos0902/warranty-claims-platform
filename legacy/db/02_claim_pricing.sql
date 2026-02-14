CREATE OR REPLACE PACKAGE claim_pricing AS
  PROCEDURE price_claim(p_claim_no IN VARCHAR2);
  PROCEDURE price_all;
END claim_pricing;
/

CREATE OR REPLACE PACKAGE BODY claim_pricing AS

  c_parts_markup    CONSTANT NUMBER := 0.10;
  c_handling_limit  CONSTANT NUMBER := 150;
  c_travel_step     CONSTANT NUMBER := 25;
  c_travel_rate     CONSTANT NUMBER := 0.85;
  c_travel_max      CONSTANT NUMBER := 200;

  FUNCTION coverage(p_claim IN claims%ROWTYPE, p_term IN warranty_terms%ROWTYPE) RETURN NUMBER IS
    v_months NUMBER;
  BEGIN
    IF p_claim.meter_hours > p_term.meter_limit THEN
      RETURN 0;
    END IF;
    v_months := TRUNC(MONTHS_BETWEEN(p_claim.failure_date, p_claim.purchase_date));
    IF v_months < p_term.full_months THEN
      RETURN 100;
    ELSIF v_months < p_term.end_months THEN
      RETURN ROUND(100 * (p_term.end_months - v_months) / (p_term.end_months - p_term.full_months), 1);
    END IF;
    RETURN 0;
  END coverage;

  FUNCTION labor(p_hours IN NUMBER, p_cap IN NUMBER, p_rate IN NUMBER) RETURN NUMBER IS
  BEGIN
    -- nearest half hour, then the cap for the repair code
    RETURN ROUND(LEAST(ROUND(p_hours * 2) / 2, p_cap) * p_rate, 2);
  END labor;

  FUNCTION parts(p_claim_no IN VARCHAR2) RETURN NUMBER IS
    v_total NUMBER := 0;
    v_base  NUMBER;
  BEGIN
    FOR r IN (SELECT qty, unit_cost FROM claim_lines WHERE claim_no = p_claim_no) LOOP
      v_base  := ROUND(r.qty * r.unit_cost, 2);
      v_total := v_total + v_base + LEAST(ROUND(v_base * c_parts_markup, 2), c_handling_limit);
    END LOOP;
    RETURN v_total;
  END parts;

  FUNCTION travel(p_miles IN NUMBER) RETURN NUMBER IS
  BEGIN
    RETURN ROUND(LEAST(CEIL(NVL(p_miles, 0) / c_travel_step) * c_travel_step, c_travel_max) * c_travel_rate, 2);
  END travel;

  PROCEDURE price_claim(p_claim_no IN VARCHAR2) IS
    v_claim  claims%ROWTYPE;
    v_term   warranty_terms%ROWTYPE;
    v_rate   dealers.labor_rate%TYPE;
    v_cap    repair_codes.cap_hours%TYPE;
    v_pct    NUMBER;
    v_labor  NUMBER;
    v_parts  NUMBER;
    v_travel NUMBER;
    v_total  NUMBER;
  BEGIN
    SELECT * INTO v_claim FROM claims WHERE claim_no = p_claim_no;
    SELECT labor_rate INTO v_rate FROM dealers WHERE dealer_code = v_claim.dealer_code;
    SELECT cap_hours INTO v_cap FROM repair_codes WHERE repair_code = v_claim.repair_code;
    SELECT * INTO v_term FROM warranty_terms
     WHERE model_code = v_claim.model_code AND claim_type = v_claim.claim_type;

    v_pct    := coverage(v_claim, v_term);
    v_labor  := labor(v_claim.labor_hours, v_cap, v_rate);
    v_parts  := parts(p_claim_no);
    v_travel := CASE WHEN v_pct > 0 THEN travel(v_claim.travel_miles) ELSE 0 END;

    v_total := ROUND((v_labor + v_parts) * v_pct / 100, 2);
    v_total := GREATEST(v_total - v_term.deductible, 0) + v_travel;
    IF v_claim.claim_type = 'G' THEN
      v_total := TRUNC(v_total);
    END IF;

    UPDATE claims
       SET coverage_pct = v_pct, labor_pay = v_labor, parts_pay = v_parts,
           travel_pay = v_travel, total_pay = v_total
     WHERE claim_no = p_claim_no;
  END price_claim;

  PROCEDURE price_all IS
  BEGIN
    FOR r IN (SELECT claim_no FROM claims) LOOP
      price_claim(r.claim_no);
    END LOOP;
  END price_all;

END claim_pricing;
/
