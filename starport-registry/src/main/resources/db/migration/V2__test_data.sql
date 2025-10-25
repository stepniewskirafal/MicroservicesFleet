INSERT INTO starport (code, name) VALUES ('ABC', 'Alpha Base Central');
INSERT INTO docking_bay (starport_id, bay_label, ship_class, status) VALUES (1, 'A-01', 'SCOUT', 'AVAILABLE');
INSERT INTO customer (customer_code, name) VALUES ('CUST-001', 'Weyland-Yutani');
INSERT INTO ship (customer_id, ship_id, ship_name, ship_class) VALUES (1, 'SS-Enterprise-01', 'Enterprise', 'SCOUT');