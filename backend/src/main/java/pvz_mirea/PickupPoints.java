package pvz_mirea;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

@Service
class PickupPoints {
    private final JdbcClient db;

    PickupPoints(JdbcClient db) {
        this.db = db;
    }

    record Point(long id, String name, String address, long regionId, int capacityPerHour) {}
    record Cell(long id, int number, BigDecimal freeLiters) {}
    record Shipment(long id, String code, String status, BigDecimal volumeLiters, Long cellId) {}

    record Totals(long stored, long expected, long shipments) {}
    record Operation(long id, String type, String code, OffsetDateTime time) {}
    record Overview(Totals totals, List<Shipment> shipments, List<Operation> operations) {}

    Overview overview(Accounts.User user) {
        user.requireEmployee();
        var totals = db.sql("""
                SELECT COUNT(*) FILTER (WHERE s.status IN ('STORED', 'RETURN_STORED')) AS stored,
                       COUNT(*) FILTER (WHERE s.status = 'EXPECTED') AS expected,
                       COUNT(*) AS shipments
                FROM shipments s JOIN pvz p ON p.id = s.pvz_id
                WHERE p.id = :pvz AND p.is_active
                """).param("pvz", user.pvzId()).query(Totals.class).single();
        var shipments = db.sql("""
                SELECT s.id, s.code, s.status, s.volume_liters, s.cell_id
                FROM shipments s JOIN pvz p ON p.id = s.pvz_id
                WHERE p.id = :pvz AND p.is_active
                ORDER BY s.updated_at DESC, s.id DESC LIMIT 10
                """).param("pvz", user.pvzId()).query(Shipment.class).list();
        var operations = db.sql("""
                SELECT o.id, o.type, s.code, o.ts AS time
                FROM operations o JOIN pvz p ON p.id = o.pvz_id
                LEFT JOIN shipments s ON s.id = o.shipment_id AND s.pvz_id = o.pvz_id
                WHERE p.id = :pvz AND p.is_active
                ORDER BY o.ts DESC, o.id DESC LIMIT 10
                """).param("pvz", user.pvzId()).query(Operation.class).list();
        return new Overview(totals, shipments, operations);
    }

    List<Point> points(Accounts.User user) {
        String scope = switch (user.role()) {
            case EMPLOYEE -> " AND id = :scope";
            case SUPERVISOR -> " AND region_id = :scope";
            case ANALYST -> "";
        };
        var query = db.sql("""
                SELECT id, name, address, region_id, capacity_per_hour
                FROM pvz WHERE is_active
                """ + scope + " ORDER BY id");
        if (user.role() != Accounts.Role.ANALYST) {
            query.param("scope", user.role() == Accounts.Role.EMPLOYEE ? user.pvzId() : user.regionId());
        }
        return query.query(Point.class).list();
    }

    List<Cell> cells(Accounts.User user) {
        user.requireEmployee();
        return db.sql("""
                SELECT c.id, c.number, c.capacity_liters - COALESCE(SUM(s.volume_liters), 0) AS free_liters
                FROM storage_cells c JOIN pvz p ON p.id = c.pvz_id
                LEFT JOIN shipments s ON s.cell_id = c.id
                WHERE p.id = :pvz AND p.is_active AND c.is_active
                GROUP BY c.id ORDER BY c.number
                """)
                .param("pvz", user.pvzId()).query(Cell.class).list();
    }

    Shipment shipment(Accounts.User user, String code) {
        user.requireEmployee();
        return db.sql("""
                SELECT s.id, s.code, s.status, s.volume_liters, s.cell_id
                FROM shipments s JOIN pvz p ON p.id = s.pvz_id
                WHERE s.code = :code AND p.id = :pvz AND p.is_active
                """)
                .param("code", code).param("pvz", user.pvzId()).query(Shipment.class).optional()
                .orElseThrow(() -> new ApiError(HttpStatus.NOT_FOUND, "SHIPMENT_NOT_FOUND",
                        "Отправление для вашей точки не найдено."));
    }
}
