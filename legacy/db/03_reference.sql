INSERT ALL
  INTO dealers VALUES ('D101', 'Prairie Tractor',        'NORTH', 118.00)
  INTO dealers VALUES ('D102', 'Lakeside Equipment',     'NORTH', 124.50)
  INTO dealers VALUES ('D201', 'Piedmont Ag Supply',     'SOUTH', 109.75)
  INTO dealers VALUES ('D202', 'Gulf Farm Machinery',    'SOUTH', 112.35)
  INTO dealers VALUES ('D301', 'Cascade Implement',      'WEST',  139.95)
  INTO dealers VALUES ('D302', 'High Desert Equipment',  'WEST',  131.15)
SELECT 1 FROM dual;

INSERT ALL
  INTO repair_codes VALUES ('HYD-PUMP', 'Hydraulic pump, replace',            4.25)
  INTO repair_codes VALUES ('HYD-HOSE', 'Hydraulic hose, replace',            1.50)
  INTO repair_codes VALUES ('ENG-HEAD', 'Cylinder head gasket, replace',     11.75)
  INTO repair_codes VALUES ('ENG-INJ',  'Fuel injector, replace',             3.25)
  INTO repair_codes VALUES ('TRN-CLUT', 'Clutch pack, replace',               8.00)
  INTO repair_codes VALUES ('ELE-HARN', 'Wiring harness, repair',             5.75)
  INTO repair_codes VALUES ('ELE-CTRL', 'Control module, replace',            2.00)
  INTO repair_codes VALUES ('CAB-HVAC', 'Cab air conditioning, repair',       3.75)
SELECT 1 FROM dual;

INSERT ALL
  INTO warranty_terms VALUES ('T40',  'S', 24, 36, 2000,   0.00)
  INTO warranty_terms VALUES ('T40',  'E', 36, 60, 4000, 100.00)
  INTO warranty_terms VALUES ('T40',  'G', 24, 72, 6000,   0.00)
  INTO warranty_terms VALUES ('T90',  'S', 24, 42, 2500,   0.00)
  INTO warranty_terms VALUES ('T90',  'E', 48, 71, 5000, 150.00)
  INTO warranty_terms VALUES ('T90',  'G', 24, 84, 8000,   0.00)
  INTO warranty_terms VALUES ('H250', 'S', 12, 24, 1200,   0.00)
  INTO warranty_terms VALUES ('H250', 'E', 24, 47, 3000, 250.00)
  INTO warranty_terms VALUES ('H250', 'G', 12, 60, 5000,  75.00)
SELECT 1 FROM dual;

INSERT ALL
  INTO group_control VALUES ('NORTH', 'Y')
  INTO group_control VALUES ('SOUTH', 'Y')
  INTO group_control VALUES ('WEST',  'Y')
SELECT 1 FROM dual;
COMMIT;
