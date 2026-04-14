package warranty.commons;

import java.util.Map;
import org.springframework.http.HttpStatus;

/** Carries a status, a stable code for clients, and a sentence for people. */
public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final transient Map<String, Object> extra;

    public ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, Map.of());
    }

    public ApiException(HttpStatus status, String code, String message, Map<String, Object> extra) {
        super(message);
        this.status = status;
        this.code = code;
        this.extra = extra;
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }
    public Map<String, Object> extra() { return extra; }
}
