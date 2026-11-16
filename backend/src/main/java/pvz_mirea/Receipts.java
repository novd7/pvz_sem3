package pvz_mirea;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;

@Service
class Receipts {
    private final JdbcClient db;
    private final Accounts accounts;

    Receipts(JdbcClient db, Accounts accounts) {
        this.db = db;
        this.accounts = accounts;
    }

    private record Shipment(long id, String status, BigDecimal volumeLiters) {}
    private record Previous(String requestHash, String responseBody) {}

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public String receive(long userId, String key, String code, long cellId) {
        // Serialize requests sharing a user-scoped idempotency key across application instances.
        Accounts.User user = accounts.lockCurrent(userId);
        user.requireEmployee();
        String hash = requestHash(user.pvzId(), code, cellId);
        var previous = db.sql("""
                SELECT request_hash, response_body::text AS response_body
                FROM idempotency_requests WHERE user_id = :user AND idempotency_key = :key
                """).param("user", user.id()).param("key", key).query(Previous.class).optional();
        if (previous.isPresent()) {
            if (!previous.get().requestHash().equals(hash)) {
                throw conflict("IDEMPOTENCY_CONFLICT", "Этот ключ уже использован с другими данными.");
            }
            return previous.get().responseBody();
        }

        long pvz = user.pvzId();
        db.sql("SELECT id FROM pvz WHERE id = :pvz AND is_active FOR SHARE")
                .param("pvz", pvz).query(Long.class).optional()
                .orElseThrow(() -> conflict("PVZ_INACTIVE", "Пункт выдачи отключён."));

        Shipment shipment = db.sql("""
                SELECT id, status, volume_liters FROM shipments
                WHERE code = :code AND pvz_id = :pvz FOR UPDATE
                """).param("code", code).param("pvz", pvz).query(Shipment.class).optional()
                .orElseThrow(() -> new ApiError(HttpStatus.NOT_FOUND, "SHIPMENT_NOT_FOUND",
                        "Отправление для вашей точки не найдено."));
        if (!"EXPECTED".equals(shipment.status())) {
            throw conflict("SHIPMENT_STATUS", "Принять можно только ожидаемое отправление.");
        }

        // Every writer changing cell occupancy must lock the cell before checking capacity.
        BigDecimal capacity = db.sql("""
                SELECT capacity_liters FROM storage_cells
                WHERE id = :cell AND pvz_id = :pvz AND is_active FOR UPDATE
                """).param("cell", cellId).param("pvz", pvz).query(BigDecimal.class).optional()
                .orElseThrow(() -> conflict("CELL_UNAVAILABLE", "Ячейка недоступна для вашей точки."));
        BigDecimal occupied = db.sql("""
                SELECT COALESCE(SUM(volume_liters), 0) FROM shipments WHERE cell_id = :cell
                """).param("cell", cellId).query(BigDecimal.class).single();
        BigDecimal free = capacity.subtract(occupied).subtract(shipment.volumeLiters());
        if (free.signum() < 0) {
            throw conflict("CELL_FULL", "Свободного объёма в ячейке недостаточно.");
        }

        OffsetDateTime time = db.sql("SELECT clock_timestamp()").query(OffsetDateTime.class).single();
        verifySchedule(pvz, time);
        db.sql("""
                UPDATE shipments SET status = 'STORED', cell_id = :cell, updated_at = :time,
                    pickup_deadline = COALESCE(pickup_deadline,
                        (CAST(:time AS timestamptz) AT TIME ZONE 'Europe/Moscow')::date + 7)
                WHERE id = :shipment
                """).param("cell", cellId).param("time", time).param("shipment", shipment.id()).update();
        long operation = db.sql("""
                INSERT INTO operations (pvz_id, shipment_id, user_id, ts, type, from_status, to_status, to_cell_id)
                VALUES (:pvz, :shipment, :user, :time, 'RECEIPT', 'EXPECTED', 'STORED', :cell)
                RETURNING id
                """).param("pvz", pvz).param("shipment", shipment.id()).param("user", user.id())
                .param("time", time).param("cell", cellId).query(Long.class).single();
        return db.sql("""
                INSERT INTO idempotency_requests
                    (user_id, idempotency_key, request_hash, http_method, request_path, response_status, response_body)
                VALUES (:user, :key, :hash, 'POST', '/api/receipts', 201,
                    jsonb_build_object('id', CAST(:shipment AS bigint), 'status', 'STORED',
                        'cellId', CAST(:cell AS bigint), 'operationId', CAST(:operation AS bigint),
                        'freeLiters', CAST(:free AS numeric)))
                RETURNING response_body::text
                """).param("user", user.id()).param("key", key).param("hash", hash)
                .param("shipment", shipment.id()).param("cell", cellId).param("operation", operation)
                .param("free", free).query(String.class).single();
    }

    void recordRejection(Accounts.User user, ApiError error) {
        db.sql("""
                INSERT INTO error_logs (source, user_id, pvz_id, action, error_code, reason)
                VALUES ('OPERATION', :user, :pvz, 'RECEIPT', :code, :reason)
                """).param("user", user.id()).param("pvz", user.pvzId())
                .param("code", error.code()).param("reason", error.getReason()).update();
    }

    private void verifySchedule(long pvz, OffsetDateTime time) {
        boolean open = db.sql("""
                WITH local AS (SELECT CAST(:time AS timestamptz) AT TIME ZONE 'Europe/Moscow' AS value)
                SELECT TRUE FROM pvz_schedules, local
                WHERE pvz_id = :pvz AND weekday = EXTRACT(ISODOW FROM local.value) - 1
                    AND local.value::time >= open_time AND local.value::time < close_time FOR SHARE OF pvz_schedules
                """).param("time", time).param("pvz", pvz).query(Boolean.class).optional().orElse(false);
        if (!open) {
            throw conflict("PVZ_CLOSED", "Пункт выдачи сейчас закрыт по расписанию.");
        }
    }

    private static ApiError conflict(String code, String message) {
        return new ApiError(HttpStatus.CONFLICT, code, message);
    }

    private static String requestHash(long pvzId, String code, long cellId) {
        String canonical = "POST\n/api/receipts\n" + pvzId + "\n" + code + "\n" + cellId;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }
}
