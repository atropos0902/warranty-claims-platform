-- Claims schema of the old application.
CREATE TABLE dealers (
  dealer_code   VARCHAR2(10) PRIMARY KEY,
  dealer_name   VARCHAR2(80) NOT NULL,
  group_code    VARCHAR2(10) NOT NULL,
  labor_rate    NUMBER(8,2)  NOT NULL
);

CREATE TABLE repair_codes (
  repair_code   VARCHAR2(10) PRIMARY KEY,
  description   VARCHAR2(120) NOT NULL,
  cap_hours     NUMBER(5,2) NOT NULL
);

CREATE TABLE warranty_terms (
  model_code    VARCHAR2(10),
  claim_type    CHAR(1),
  full_months   NUMBER(3) NOT NULL,
  end_months    NUMBER(3) NOT NULL,
  meter_limit   NUMBER(6) NOT NULL,
  deductible    NUMBER(8,2) NOT NULL,
  CONSTRAINT pk_terms PRIMARY KEY (model_code, claim_type)
);

CREATE TABLE claims (
  claim_no       VARCHAR2(20) PRIMARY KEY,
  dealer_code    VARCHAR2(10) NOT NULL REFERENCES dealers,
  model_code     VARCHAR2(10) NOT NULL,
  serial_no      VARCHAR2(30) NOT NULL,
  claim_type     CHAR(1) NOT NULL,
  purchase_date  DATE NOT NULL,
  failure_date   DATE NOT NULL,
  meter_hours    NUMBER(8,1),
  repair_code    VARCHAR2(10) NOT NULL REFERENCES repair_codes,
  labor_hours    NUMBER(6,2) NOT NULL,
  travel_miles   NUMBER(7,1),
  notes          VARCHAR2(2000),
  status         VARCHAR2(12) DEFAULT 'SUBMITTED' NOT NULL,
  line_count     NUMBER(3) DEFAULT 0 NOT NULL,
  version_no     NUMBER(9) DEFAULT 1 NOT NULL,
  coverage_pct   NUMBER(5,1),
  labor_pay      NUMBER(10,2),
  parts_pay      NUMBER(10,2),
  travel_pay     NUMBER(10,2),
  total_pay      NUMBER(10,2)
);

CREATE TABLE claim_lines (
  claim_no   VARCHAR2(20) REFERENCES claims ON DELETE CASCADE,
  line_no    NUMBER(3),
  part_no    VARCHAR2(20) NOT NULL,
  qty        NUMBER(6) NOT NULL,
  unit_cost  NUMBER(12,4) NOT NULL,
  CONSTRAINT pk_lines PRIMARY KEY (claim_no, line_no)
);

-- Dealer groups that may still write here. Missing means open.
CREATE TABLE group_control (
  group_code     VARCHAR2(10) PRIMARY KEY,
  writes_allowed CHAR(1) DEFAULT 'Y' NOT NULL
);

-- Event ids the platform has already replayed into this database.
CREATE TABLE replay_inbox (
  event_id VARCHAR2(36) PRIMARY KEY
);

-- Feed for the local change copier. AWS DMS reads the redo log and does not use it.
CREATE TABLE claim_change_log (
  seq       NUMBER GENERATED ALWAYS AS IDENTITY (ORDER NOCACHE) PRIMARY KEY,
  claim_no  VARCHAR2(20) NOT NULL
);

-- One transaction at a time adds to the feed, so seq order is commit order.
CREATE TABLE change_log_gate (id NUMBER PRIMARY KEY);
INSERT INTO change_log_gate VALUES (1);
COMMIT;

CREATE OR REPLACE TRIGGER trg_claims_log
AFTER INSERT OR UPDATE ON claims FOR EACH ROW
DECLARE
  v NUMBER;
BEGIN
  SELECT id INTO v FROM change_log_gate WHERE id = 1 FOR UPDATE;
  INSERT INTO claim_change_log (claim_no) VALUES (:NEW.claim_no);
END;
/

CREATE OR REPLACE TRIGGER trg_claims_gate
BEFORE INSERT OR UPDATE ON claims FOR EACH ROW
DECLARE
  v_allowed CHAR(1);
BEGIN
  IF SYS_CONTEXT('USERENV', 'CLIENT_IDENTIFIER') = 'replay' THEN
    RETURN;
  END IF;
  BEGIN
    -- The lock makes a write in flight finish before the group is closed.
    SELECT gc.writes_allowed INTO v_allowed
      FROM group_control gc JOIN dealers d ON d.group_code = gc.group_code
     WHERE d.dealer_code = :NEW.dealer_code
       FOR UPDATE OF gc.writes_allowed;
  EXCEPTION
    WHEN NO_DATA_FOUND THEN v_allowed := 'Y';
  END;
  IF v_allowed = 'N' THEN
    RAISE_APPLICATION_ERROR(-20010, 'Dealer group has moved to the new platform');
  END IF;
  -- Writing prices back is not an edit. Anything else is, and gets a new version.
  IF UPDATING AND NOT UPDATING('TOTAL_PAY') THEN
    :NEW.version_no := :OLD.version_no + 1;
  END IF;
END;
/
