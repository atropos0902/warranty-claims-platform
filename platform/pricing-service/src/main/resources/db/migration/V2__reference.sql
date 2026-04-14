INSERT INTO dealer_rate VALUES
  ('D101', 118.00), ('D102', 124.50), ('D201', 109.75), ('D202', 112.35), ('D301', 139.95), ('D302', 131.15);

INSERT INTO repair_code VALUES
  ('HYD-PUMP', 'Hydraulic pump, replace',        4.25),
  ('HYD-HOSE', 'Hydraulic hose, replace',        1.50),
  ('ENG-HEAD', 'Cylinder head gasket, replace', 11.75),
  ('ENG-INJ',  'Fuel injector, replace',         3.25),
  ('TRN-CLUT', 'Clutch pack, replace',           8.00),
  ('ELE-HARN', 'Wiring harness, repair',         5.75),
  ('ELE-CTRL', 'Control module, replace',        2.00),
  ('CAB-HVAC', 'Cab air conditioning, repair',   3.75);

INSERT INTO warranty_term VALUES
  ('T40',  'S', 24, 36, 2000,   0.00), ('T40',  'E', 36, 60, 4000, 100.00), ('T40',  'G', 24, 72, 6000,  0.00),
  ('T90',  'S', 24, 42, 2500,   0.00), ('T90',  'E', 48, 71, 5000, 150.00), ('T90',  'G', 24, 84, 8000,  0.00),
  ('H250', 'S', 12, 24, 1200,   0.00), ('H250', 'E', 24, 47, 3000, 250.00), ('H250', 'G', 12, 60, 5000, 75.00);

INSERT INTO rule_flag VALUES
  ('cap-before-rounding',            FALSE, '0001-labor-rounding'),
  ('hold-missing-meter',             FALSE, '0002-other-manual-gaps'),
  ('handling-limit-per-claim',       FALSE, '0002-other-manual-gaps'),
  ('reject-failure-before-purchase', FALSE, '0002-other-manual-gaps');
