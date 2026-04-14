CREATE TABLE dealer_rate (
  dealer_code VARCHAR(10) PRIMARY KEY,
  labor_rate  DECIMAL(8,2) NOT NULL
);

CREATE TABLE repair_code (
  repair_code VARCHAR(10) PRIMARY KEY,
  description VARCHAR(120) NOT NULL,
  cap_hours   DECIMAL(5,2) NOT NULL
);

CREATE TABLE warranty_term (
  model_code  VARCHAR(10),
  claim_type  CHAR(1),
  full_months INT NOT NULL,
  end_months  INT NOT NULL,
  meter_limit DECIMAL(7,0) NOT NULL,
  deductible  DECIMAL(8,2) NOT NULL,
  PRIMARY KEY (model_code, claim_type)
);

-- Changed by migration only, so every change is reviewed next to its ADR.
CREATE TABLE rule_flag (
  flag_name VARCHAR(60) PRIMARY KEY,
  enabled   BOOLEAN NOT NULL DEFAULT FALSE,
  adr       VARCHAR(60) NOT NULL
);

CREATE TABLE comparison (
  claim_no       VARCHAR(20) PRIMARY KEY,
  group_code     VARCHAR(10) NOT NULL,
  claim_version  INT NOT NULL,
  legacy_total   DECIMAL(12,2) NOT NULL,
  platform_total DECIMAL(12,2) NOT NULL,
  differs        BOOLEAN NOT NULL,
  split_by       VARCHAR(60),
  input_json     JSON NOT NULL,
  explanation    VARCHAR(500),
  explained_by   VARCHAR(80),
  KEY ix_comparison_group (group_code, differs)
);
