-- Every draft that was shown or withheld, with what the model saw and did.
CREATE TABLE draft (
  id             BIGINT AUTO_INCREMENT PRIMARY KEY,
  claim_no       VARCHAR(20) NOT NULL,
  claim_version  INT NOT NULL,
  model          VARCHAR(80) NOT NULL,
  status         ENUM('DRAFTED','WITHHELD') NOT NULL,
  decision       VARCHAR(10),
  clause_id      VARCHAR(10),
  quote          VARCHAR(1000),
  withheld_why   VARCHAR(40),
  retrieved      JSON NOT NULL,
  tool_calls     JSON NOT NULL,
  latency_ms     INT NOT NULL,
  requested_by   VARCHAR(80) NOT NULL,
  KEY ix_draft_claim (claim_no, claim_version)
);
