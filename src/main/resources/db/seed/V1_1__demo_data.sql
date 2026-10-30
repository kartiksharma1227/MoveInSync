-- Synthetic demo data, loaded only with the 'dev' profile. All people and contact details are fake.

INSERT INTO tenant (id, name) VALUES
    ('ACME', 'Acme Corp'),
    ('GLOBEX', 'Globex Ltd');

INSERT INTO office (tenant_id, code, name) VALUES
    ('ACME', 'BLR-01', 'Bengaluru Tech Park'),
    ('ACME', 'BLR-02', 'Bengaluru Whitefield'),
    ('ACME', 'HYD-01', 'Hyderabad Hitec City'),
    ('GLOBEX', 'PUN-01', 'Pune Hinjewadi'),
    ('GLOBEX', 'DEL-01', 'Delhi Aerocity');

INSERT INTO employee (tenant_id, employee_id, status, hr_version, row_hash, last_hr_change_at,
                      name, email, phone, manager_id, office_code, shift, pickup_point, special_assistance) VALUES
    ('ACME',   'E1001', 'ACTIVE', 5, '', now() - interval '10 days', 'Employee 1001', 'e1001@acme.test',   '+919800001001', 'E1005', 'BLR-01', 'S0900', 'Gate 3, Tower A', NULL),
    ('ACME',   'E1002', 'ACTIVE', 3, '', now() - interval '10 days', 'Employee 1002', 'e1002@acme.test',   '+919800001002', 'E1005', 'BLR-01', 'S1400', 'Main Lobby',      'Wheelchair access'),
    ('ACME',   'E1003', 'ACTIVE', 1, '', now() - interval '10 days', 'Employee 1003', 'e1003@acme.test',   '+919800001003', 'E1005', 'BLR-02', 'S0900', NULL,              NULL),
    ('ACME',   'E1004', 'ACTIVE', 2, '', now() - interval '10 days', 'Employee 1004', 'e1004@acme.test',   '+919800001004', 'E1005', 'HYD-01', 'S2200', NULL,              NULL),
    ('ACME',   'E1005', 'ACTIVE', 1, '', now() - interval '10 days', 'Employee 1005', 'e1005@acme.test',   '+919800001005', NULL,    'BLR-01', 'S0900', NULL,              NULL),
    ('GLOBEX', 'G2001', 'ACTIVE', 1, '', now() - interval '10 days', 'Employee 2001', 'g2001@globex.test', '+919800002001', NULL,    'PUN-01', 'S0900', 'Gate 1',          NULL),
    ('GLOBEX', 'G2002', 'ACTIVE', 1, '', now() - interval '10 days', 'Employee 2002', 'g2002@globex.test', '+919800002002', 'G2001', 'DEL-01', 'S1400', NULL,              NULL);

-- Same canonical form as RowHasher: HR fields joined by '|', nulls as empty strings.
UPDATE employee SET row_hash = encode(sha256(convert_to(
    concat_ws('|', name, email, phone, coalesce(manager_id, ''), office_code, shift), 'UTF8')), 'hex');

INSERT INTO trip (id, tenant_id, pickup_at, capacity, seats_booked) VALUES
    (1, 'ACME',   now() + interval '1 day',  4, 2),
    (2, 'ACME',   now() + interval '2 days', 4, 1),
    (3, 'ACME',   now() - interval '1 day',  4, 1),   -- past trip: leaving must not rewrite history
    (4, 'GLOBEX', now() + interval '1 day',  6, 1);
SELECT setval('trip_id_seq', 4);

INSERT INTO booking (tenant_id, employee_id, trip_id, status) VALUES
    ('ACME',   'E1001', 1, 'CONFIRMED'),
    ('ACME',   'E1002', 1, 'CONFIRMED'),
    ('ACME',   'E1001', 2, 'CONFIRMED'),
    ('ACME',   'E1001', 3, 'CONFIRMED'),
    ('GLOBEX', 'G2001', 4, 'CONFIRMED');
