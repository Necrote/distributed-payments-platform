package com.vivekpatel.payments.api;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.vivekpatel.payments.domain.IllegalStateTransitionException;
import com.vivekpatel.payments.service.PaymentNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * The error contract: every failure leaves this service as an RFC 7807 {@code application/problem+json}
 * body, and nothing else.
 *
 * <p>Extending {@link ResponseEntityExceptionHandler} means Spring MVC's own failures (malformed
 * UUID in the path, unsupported media type, wrong method) already come out as ProblemDetails. This
 * class only overrides the two where the default says "invalid" without saying <i>which field</i>,
 * and adds the domain and catch-all cases.
 *
 * <p>Two rules this class exists to enforce:
 *
 * <ul>
 *   <li><b>No stack traces, no exception messages from libraries.</b> A Jackson or Hibernate message
 *       names internal classes and sometimes echoes input. Unexpected exceptions are logged in full
 *       here and the client gets a generic 500.</li>
 *   <li><b>No rejected values.</b> Field errors name the field and the rule, never the value: the
 *       value might be a {@code paymentMethodToken}, and error bodies end up in client logs.</li>
 * </ul>
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /**
     * One offending input. {@code field} is the JSON property name - the same as the Java component
     * name, because the request records do not rename anything with {@code @JsonProperty}.
     */
    public record FieldViolation(String field, String message) {
    }

    /** Bean Validation failures on a {@code @Valid @RequestBody}. */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        List<FieldViolation> errors =
                Stream.concat(
                                ex.getBindingResult().getFieldErrors().stream()
                                        .map(e -> violation(e.getField(), e.getDefaultMessage())),
                                ex.getBindingResult().getGlobalErrors().stream()
                                        .map(e -> violation(e.getObjectName(), e.getDefaultMessage())))
                        // Validator iteration order is unspecified; sort so the body is stable.
                        .sorted(Comparator.comparing(FieldViolation::field)
                                .thenComparing(FieldViolation::message))
                        .toList();
        ProblemDetail body = ProblemDetail.forStatusAndDetail(status, "Request validation failed");
        body.setProperty("errors", errors);
        return handleExceptionInternal(ex, body, headers, status, request);
    }

    /**
     * The body could not be turned into the request record at all: broken JSON, or a value of the
     * wrong type such as {@code "amount": "ten"}. The default detail is just "Failed to read
     * request"; when Jackson knows where it failed, name that field.
     */
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(status, "Request body is not readable");
        if (ex.getCause() instanceof JsonMappingException mapping && !mapping.getPath().isEmpty()) {
            body.setDetail("Request body has a value of the wrong type");
            body.setProperty(
                    "errors", List.of(violation(jsonPath(mapping), "has a value of the wrong type")));
        }
        return handleExceptionInternal(ex, body, headers, status, request);
    }

    @ExceptionHandler(PaymentNotFoundException.class)
    public ProblemDetail handlePaymentNotFound(PaymentNotFoundException ex) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        body.setTitle("Payment not found");
        return body;
    }

    /**
     * The request was well-formed but the payment is in the wrong state for it - capturing a
     * {@code CREATED} payment, refunding an {@code AUTHORIZED} one. 409, not 400: the same request
     * could succeed against the same resource once its state changes.
     */
    @ExceptionHandler(IllegalStateTransitionException.class)
    public ProblemDetail handleIllegalTransition(IllegalStateTransitionException ex) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT,
                "Payment is " + ex.getFrom() + " and cannot move to " + ex.getTo());
        body.setTitle("Illegal payment state transition");
        body.setProperty("currentStatus", ex.getFrom());
        return body;
    }

    /**
     * Anything nobody anticipated. The full exception goes to the log; the client learns only that
     * it was our fault.
     *
     * <p>Phase 6 note: once Spring Security is on the classpath, {@code AccessDeniedException} thrown
     * from a controller would land here as a 500. Map it explicitly before that happens.
     */
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception on {} {}", request.getMethod(), request.getRequestURI(), ex);
        return ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred");
    }

    private static FieldViolation violation(String field, String message) {
        return new FieldViolation(field, Objects.requireNonNullElse(message, "is invalid"));
    }

    /** {@code items[2].amount}-style path from Jackson's reference chain. */
    private static String jsonPath(JsonMappingException ex) {
        return ex.getPath().stream()
                .map(ref -> ref.getFieldName() != null
                        ? "." + ref.getFieldName()
                        : "[" + ref.getIndex() + "]")
                .collect(Collectors.joining())
                .replaceFirst("^\\.", "");
    }
}
