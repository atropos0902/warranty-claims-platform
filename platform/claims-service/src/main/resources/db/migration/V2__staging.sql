-- DMS writes the two mirror tables. The triggers turn each arriving row into work for the loader.
CREATE TABLE staging.claims (
  claim_no      VARCHAR(20) PRIMARY KEY,
  dealer_code   VARCHAR(10) NOT NULL,
  model_code    VARCHAR(10) NOT NULL,
  serial_no     VARCHAR(30) NOT NULL,
  claim_type    CHAR(1) NOT NULL,
  purchase_date DATE NOT NULL,
  failure_date  DATE NOT NULL,
  meter_hours   DECIMAL(8,1),
  repair_code   VARCHAR(10) NOT NULL,
  labor_hours   DECIMAL(6,2) NOT NULL,
  travel_miles  DECIMAL(7,1),
  notes         VARCHAR(2000),
  status        VARCHAR(12) NOT NULL,
  line_count    INT NOT NULL,
  version_no    INT NOT NULL,
  coverage_pct  DECIMAL(5,1),
  labor_pay     DECIMAL(12,2),
  parts_pay     DECIMAL(12,2),
  travel_pay    DECIMAL(10,2),
  total_pay     DECIMAL(12,2)
);

CREATE TABLE staging.claim_lines (
  claim_no  VARCHAR(20),
  line_no   INT,
  part_no   VARCHAR(20) NOT NULL,
  qty       INT NOT NULL,
  unit_cost DECIMAL(12,4) NOT NULL,
  PRIMARY KEY (claim_no, line_no)
);

CREATE TABLE staging.change_queue (
  id       BIGINT AUTO_INCREMENT PRIMARY KEY,
  claim_no VARCHAR(20) NOT NULL
);

CREATE TRIGGER staging.claims_added AFTER INSERT ON staging.claims
  FOR EACH ROW INSERT INTO staging.change_queue (claim_no) VALUES (NEW.claim_no);
CREATE TRIGGER staging.claims_changed AFTER UPDATE ON staging.claims
  FOR EACH ROW INSERT INTO staging.change_queue (claim_no) VALUES (NEW.claim_no);
CREATE TRIGGER staging.lines_added AFTER INSERT ON staging.claim_lines
  FOR EACH ROW INSERT INTO staging.change_queue (claim_no) VALUES (NEW.claim_no);
CREATE TRIGGER staging.lines_changed AFTER UPDATE ON staging.claim_lines
  FOR EACH ROW INSERT INTO staging.change_queue (claim_no) VALUES (NEW.claim_no);

-- How far the local change copier has read. DMS keeps its own position and ignores this.
CREATE TABLE staging.copier_mark (id INT PRIMARY KEY, seq BIGINT NOT NULL);
INSERT INTO staging.copier_mark VALUES (1, 0);
