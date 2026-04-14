CREATE TABLE dealer_group (
  group_code VARCHAR(10) PRIMARY KEY,
  group_name VARCHAR(60) NOT NULL,
  owner      ENUM('LEGACY','MOVING','PLATFORM','RETURNING') NOT NULL DEFAULT 'LEGACY'
);

CREATE TABLE dealer (
  dealer_code VARCHAR(10) PRIMARY KEY,
  dealer_name VARCHAR(80) NOT NULL,
  group_code  VARCHAR(10) NOT NULL,
  FOREIGN KEY (group_code) REFERENCES dealer_group (group_code)
);

CREATE TABLE claim (
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
  version_no    INT NOT NULL,
  coverage_pct  DECIMAL(5,1),
  labor_pay     DECIMAL(12,2),
  parts_pay     DECIMAL(12,2),
  travel_pay    DECIMAL(10,2),
  total_pay     DECIMAL(12,2),
  price_steps   JSON,
  decision_note VARCHAR(500),
  decided_by    VARCHAR(80),
  KEY ix_claim_status (status, claim_no),
  KEY ix_claim_dealer (dealer_code, claim_no),
  KEY ix_claim_serial (serial_no),
  FOREIGN KEY (dealer_code) REFERENCES dealer (dealer_code)
);

CREATE TABLE claim_line (
  claim_no  VARCHAR(20),
  line_no   INT,
  part_no   VARCHAR(20) NOT NULL,
  qty       INT NOT NULL,
  unit_cost DECIMAL(12,4) NOT NULL,
  PRIMARY KEY (claim_no, line_no),
  FOREIGN KEY (claim_no) REFERENCES claim (claim_no) ON DELETE CASCADE
);

CREATE TABLE idempotency_key (
  caller       VARCHAR(80),
  idem_key     VARCHAR(80),
  request_hash CHAR(64) NOT NULL,
  claim_no     VARCHAR(20) NOT NULL,
  PRIMARY KEY (caller, idem_key)
);

CREATE TABLE outbox (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  event_id      CHAR(36) NOT NULL UNIQUE,
  destination   ENUM('REPLAY','COMPARE') NOT NULL,
  claim_no      VARCHAR(20) NOT NULL,
  group_code    VARCHAR(10) NOT NULL,
  claim_version INT NOT NULL,
  payload       JSON NOT NULL,
  published     BOOLEAN NOT NULL DEFAULT FALSE,
  KEY ix_outbox_waiting (published, id)
);

-- One relay publishes at a time, which keeps the order of events for a claim.
CREATE TABLE job_lock (name VARCHAR(20) PRIMARY KEY);
INSERT INTO job_lock VALUES ('relay');

INSERT INTO dealer_group (group_code, group_name) VALUES
  ('NORTH', 'Northern dealers'), ('SOUTH', 'Southern dealers'), ('WEST', 'Western dealers');
INSERT INTO dealer VALUES
  ('D101', 'Prairie Tractor', 'NORTH'), ('D102', 'Lakeside Equipment', 'NORTH'),
  ('D201', 'Piedmont Ag Supply', 'SOUTH'), ('D202', 'Gulf Farm Machinery', 'SOUTH'),
  ('D301', 'Cascade Implement', 'WEST'), ('D302', 'High Desert Equipment', 'WEST');
