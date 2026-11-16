package pvz_mirea;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
class Snapshots {
    private final JdbcClient db;
    private final Accounts accounts;

    Snapshots(JdbcClient db, Accounts accounts) {
        this.db = db;
        this.accounts = accounts;
    }

    record Snapshot(UUID id, String time, byte[] bytes) {}

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Snapshot export(long userId) {
        OffsetDateTime timestamp = db.sql("SELECT transaction_timestamp()")
                .query(OffsetDateTime.class).single();
        Accounts.User user = accounts.reload(userId);
        String scope = switch (user.role()) {
            case EMPLOYEE -> " WHERE p.id = :scope";
            case SUPERVISOR -> " WHERE p.region_id = :scope";
            case ANALYST -> "";
        };
        String time = timestamp.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        UUID id = UUID.randomUUID();
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            var writer = new OutputStreamWriter(zip, StandardCharsets.UTF_8);
            zip.putNextEntry(new ZipEntry("snapshot.csv"));
            writeRow(writer, "snapshot_id", "exported_at");
            writeRow(writer, id.toString(), time);
            writer.flush();
            zip.closeEntry();

            writeTable(zip, writer, "pvz.csv", user, """
                    SELECT p.id, p.address, p.region_id, p.capacity_per_hour FROM pvz p
                    """ + scope + " ORDER BY p.id",
                    "pvz_id", "address", "region_id", "capacity_per_hour");
            writeTable(zip, writer, "schedule.csv", user, """
                    SELECT s.pvz_id, s.weekday, s.open_time, s.close_time
                    FROM pvz_schedules s JOIN pvz p ON p.id = s.pvz_id
                    """ + scope + " ORDER BY s.pvz_id, s.weekday",
                    "pvz_id", "weekday", "open_time", "close_time");
            writeTable(zip, writer, "operations.csv", user, """
                    SELECT o.id, o.pvz_id, o.ts, CASE o.type
                        WHEN 'RECEIPT' THEN 'in'
                        WHEN 'ISSUE' THEN 'out'
                        WHEN 'CUSTOMER_RETURN' THEN 'return'
                        WHEN 'RETURN_DISPATCH' THEN 'return_dispatch'
                    END
                    FROM operations o JOIN pvz p ON p.id = o.pvz_id
                    """ + scope + " ORDER BY o.id",
                    "op_id", "pvz_id", "ts", "type");
        } catch (IOException failure) {
            throw new UncheckedIOException("Cannot create CSV snapshot", failure);
        }
        return new Snapshot(id, time, bytes.toByteArray());
    }

    private void writeTable(ZipOutputStream zip, Writer writer, String file, Accounts.User user,
                            String sql, String... header) throws IOException {
        zip.putNextEntry(new ZipEntry(file));
        writeRow(writer, header);
        var query = db.sql(sql);
        if (user.role() != Accounts.Role.ANALYST) {
            query.param("scope", user.role() == Accounts.Role.EMPLOYEE ? user.pvzId() : user.regionId());
        }
        query.query(rs -> {
            try {
                writeRow(writer, values(rs, header));
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
        });
        writer.flush();
        zip.closeEntry();
    }

    private static String[] values(ResultSet rs, String[] header) throws SQLException {
        String[] values = new String[header.length];
        for (int index = 0; index < header.length; index++) {
            int column = index + 1;
            values[index] = "ts".equals(header[index])
                    ? rs.getObject(column, OffsetDateTime.class).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                    : rs.getString(column);
        }
        return values;
    }

    private static void writeRow(Writer writer, String... fields) throws IOException {
        for (int index = 0; index < fields.length; index++) {
            if (index > 0) {
                writer.write(',');
            }
            String field = fields[index];
            if (field.indexOf(',') >= 0 || field.indexOf('"') >= 0
                    || field.indexOf('\n') >= 0 || field.indexOf('\r') >= 0) {
                writer.write('"');
                writer.write(field.replace("\"", "\"\""));
                writer.write('"');
            } else {
                writer.write(field);
            }
        }
        writer.write("\r\n");
    }
}
