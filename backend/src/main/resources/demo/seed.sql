-- Synthetic data for the dedicated demo database. Safe to run again after a restart.
INSERT INTO regions (name) VALUES ('Демо Север'), ('Демо Юг')
ON CONFLICT (name) DO NOTHING;

INSERT INTO pvz (name, address, region_id, capacity_per_hour)
SELECT points.name, points.address, region.id, points.capacity
FROM (VALUES ('DEMO-1', 'Учебная улица, 1', 'Демо Север', 12),
             ('DEMO-2', 'Учебная улица, 2', 'Демо Север', 10),
             ('DEMO-3', 'Учебная улица, 3', 'Демо Юг', 8))
    AS points(name, address, region_name, capacity)
JOIN regions region ON region.name = points.region_name
WHERE NOT EXISTS (SELECT 1 FROM pvz existing
    WHERE existing.name = points.name AND existing.region_id = region.id);

INSERT INTO pvz_schedules (pvz_id, weekday, open_time, close_time)
SELECT p.id, d.weekday, TIME '00:00', TIME '24:00'
FROM pvz p CROSS JOIN generate_series(0, 6) AS d(weekday)
WHERE p.name IN ('DEMO-1', 'DEMO-2', 'DEMO-3')
ON CONFLICT (pvz_id, weekday) DO NOTHING;

INSERT INTO storage_cells (pvz_id, number, capacity_liters)
SELECT p.id, cells.number, cells.capacity
FROM pvz p CROSS JOIN (VALUES (1, 10), (2, 20), (3, 1)) AS cells(number, capacity)
WHERE p.name IN ('DEMO-1', 'DEMO-2', 'DEMO-3')
ON CONFLICT (pvz_id, number) DO NOTHING;

INSERT INTO users (login, password_hash, full_name, role, pvz_id, region_id)
VALUES
    ('employee', 'f157ce67a7d8dd1f75728c4a4c42062d$2c864748839132a4ab0d10ae818011f22cfebd5ed9e04fbacf0c9a36f2c4ccb7', 'Сотрудник учебной точки №1', 'EMPLOYEE', (SELECT id FROM pvz WHERE name = 'DEMO-1'), NULL),
    ('employee-peer', '08e4a48dd2ab7d512f941b3acb4c89f6$5d331d694810c3b6c0d4f040dd22a6a8295651efc454fd7cd8de68a7cb7d9a16', 'Второй сотрудник точки', 'EMPLOYEE', (SELECT id FROM pvz WHERE name = 'DEMO-1'), NULL),
    ('employee-other', '5ec597b40f3fbf0f9e51053d38dadf4d$421d7facb849e74c43d65890b196b766074f3da1e5704b37dcc7304bd0652d9f', 'Сотрудник другой точки', 'EMPLOYEE', (SELECT id FROM pvz WHERE name = 'DEMO-3'), NULL),
    ('supervisor', '0afd0fcaa4c98be1dc697c0b293cbaed$635dce9f202fa23bdda66d10ce5581b099b1de3d4bae273a7ecd6d9f07735221', 'Супервайзер Северного региона', 'SUPERVISOR', NULL, (SELECT id FROM regions WHERE name = 'Демо Север')),
    ('analyst', '8644b2ed71b4a0162e7b0c6ac58c80e8$abcfb7882f7ea17051af5331bda4dcc5090a551315ac584a40496f4f92688b0a', 'Аналитик сети', 'ANALYST', NULL, NULL)
ON CONFLICT (login) DO NOTHING;

INSERT INTO shipments (code, pvz_id, volume_liters, pickup_code, return_code)
SELECT fixture.code, p.id, fixture.volume, fixture.code || '-PICKUP', fixture.code || '-RETURN'
FROM (VALUES ('DEMO-001', 'DEMO-1', 3), ('DEMO-002', 'DEMO-1', 7),
             ('DEMO-003', 'DEMO-1', 7), ('DEMO-004', 'DEMO-2', 4),
             ('DEMO-005', 'DEMO-3', 5))
    AS fixture(code, point_name, volume)
JOIN pvz p ON p.name = fixture.point_name
ON CONFLICT (code) DO NOTHING;
