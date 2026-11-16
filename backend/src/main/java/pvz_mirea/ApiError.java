package pvz_mirea;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

final class ApiError extends ResponseStatusException {
    private final String code;

    ApiError(HttpStatus status, String code, String message) {
        super(status, message);
        this.code = code;
        getBody().setProperty("code", code);
    }

    String code() {
        return code;
    }
}
