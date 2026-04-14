package warranty.commons;

import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

@RestControllerAdvice
public class ProblemHandler {

    @ExceptionHandler
    ProblemDetail known(ApiException e) {
        var p = ProblemDetail.forStatusAndDetail(e.status(), e.getMessage());
        p.setProperty("code", e.code());
        e.extra().forEach(p::setProperty);
        return p;
    }

    @ExceptionHandler
    ProblemDetail invalidBody(MethodArgumentNotValidException e) {
        return invalid(e.getBindingResult().getFieldErrors().stream());
    }

    /** Spring raises this one instead when a header or query parameter is validated next to the body. */
    @ExceptionHandler
    ProblemDetail invalidCall(HandlerMethodValidationException e) {
        return invalid(e.getBeanResults().stream().flatMap(r -> r.getFieldErrors().stream()));
    }

    private static ProblemDetail invalid(Stream<FieldError> errors) {
        Map<String, String> fields = new TreeMap<>();
        errors.forEach(f -> fields.putIfAbsent(f.getField(), String.valueOf(f.getDefaultMessage())));
        var p = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Some fields need attention");
        p.setProperty("code", "INVALID");
        p.setProperty("fields", fields);
        return p;
    }
}
