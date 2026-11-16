package pvz_mirea;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;

@Service
class Accounts {
    private static final String USER_ID = "pvz.userId";
    private static final int HASH_ITERATIONS = 600_000;
    private static final String DUMMY_HASH = "00000000000000000000000000000000$"
            + "0000000000000000000000000000000000000000000000000000000000000000";
    private final JdbcClient db;

    Accounts(JdbcClient db) {
        this.db = db;
    }

    enum Role { EMPLOYEE, SUPERVISOR, ANALYST }

    record User(long id, String login, String name, Role role, Long pvzId, Long regionId) {
        void requireEmployee() {
            if (role != Role.EMPLOYEE) {
                throw new ApiError(HttpStatus.FORBIDDEN, "EMPLOYEE_ONLY", "Операция доступна сотруднику ПВЗ.");
            }
        }
    }

    private record Credentials(long id, String hash) {}

    User login(HttpServletRequest request, String login, String password) {
        var credentials = db.sql("""
                SELECT id, password_hash FROM users WHERE login = :login AND is_active
                """)
                .param("login", login)
                .query((rs, row) -> new Credentials(rs.getLong("id"), rs.getString("password_hash")))
                .optional();
        boolean matches = verify(password, credentials.map(Credentials::hash).orElse(DUMMY_HASH));
        if (!matches || credentials.isEmpty()) {
            throw new ApiError(HttpStatus.UNAUTHORIZED, "INVALID_LOGIN", "Неверный логин или пароль.");
        }

        User user = reload(credentials.get().id());
        logout(request);
        request.getSession().setAttribute(USER_ID, user.id());
        return user;
    }

    void logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
    }

    User current(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null || !(session.getAttribute(USER_ID) instanceof Long id)) {
            throw loginRequired();
        }
        return reload(id);
    }

    User lockCurrent(long id) {
        return find(id, true);
    }

    User reload(long id) {
        return find(id, false);
    }

    private User find(long id, boolean locked) {
        String sql = """
                SELECT id, login, full_name, role, pvz_id, region_id
                FROM users WHERE id = :id AND is_active
                """;
        return db.sql(locked ? sql + " FOR UPDATE" : sql)
                .param("id", id)
                .query((rs, row) -> new User(rs.getLong("id"), rs.getString("login"), rs.getString("full_name"),
                        Role.valueOf(rs.getString("role")), rs.getObject("pvz_id", Long.class),
                        rs.getObject("region_id", Long.class)))
                .optional().orElseThrow(Accounts::loginRequired);
    }

    private static ApiError loginRequired() {
        return new ApiError(HttpStatus.UNAUTHORIZED, "LOGIN_REQUIRED", "Войдите в систему.");
    }

    private static boolean verify(String password, String stored) {
        String[] parts = stored.split("\\$", -1);
        if (parts.length != 2 || parts[0].length() != 32 || parts[1].length() != 64) {
            return false;
        }

        PBEKeySpec key = null;
        try {
            var hex = HexFormat.of();
            key = new PBEKeySpec(password.toCharArray(), hex.parseHex(parts[0]), HASH_ITERATIONS, 256);
            byte[] derived = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(key).getEncoded();
            return MessageDigest.isEqual(derived, hex.parseHex(parts[1]));
        } catch (IllegalArgumentException invalidHash) {
            return false;
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("PBKDF2 is unavailable", failure);
        } finally {
            if (key != null) {
                key.clearPassword();
            }
        }
    }
}
