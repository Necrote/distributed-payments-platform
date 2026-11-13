package com.vivekpatel.simulator;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Two APIs in one service.
 *
 * <p><b>The fake processor</b> ({@code /authorize}, {@code /capture}, {@code /refund}) is what
 * payment-service calls. <b>The control plane</b> ({@code /control/*}) is what your tests and your
 * demos call to decide how the processor is going to misbehave next.
 *
 * <p>Keeping the control plane in the same service is a conscious trade-off: it is not how you
 * would build a real test double for a shared environment, but it makes a failure scenario a single
 * curl, which means the scenarios actually get run.
 */
@RestController
public class SimulatorController {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SimulatorController.class);

    /** Fixed, not configurable: the 429 scenario is about the caller honouring it, not tuning it. */
    private static final int RETRY_AFTER_SECONDS = 1;

    private final SimulatorProperties properties;

    /**
     * Approved authorisations by id, for capture and refund (Day 14) to look up. In memory on
     * purpose - a restart is a reset. Concurrent because SLOW mode means many requests are in
     * flight at once on different threads.
     */
    private final Map<String, AuthorizeRequest> authorizations = new ConcurrentHashMap<>();

    /**
     * The answer already given for each request id. A repeated id gets that answer and creates
     * nothing new, as a real processor's idempotency key would. Without this, a caller's safe retry
     * would really charge twice here, and the scenario would prove the opposite of what it claims.
     */
    private final Map<String, Map<String, Object>> answersByRequestId = new ConcurrentHashMap<>();

    /** When the current OUTAGE ends. Set by the control endpoint, read by request threads. */
    private volatile Instant outageEndsAt = Instant.MIN;

    private final ObjectMapper objectMapper;

    public SimulatorController(SimulatorProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * Authorise a payment.
     *
     * <p>A switch <em>expression</em> over {@link FailureMode} with no {@code default}: adding a mode
     * to the enum without handling it here is a compile error, not a silent fall-through to NORMAL.
     *
     * <p>The response echoes {@code requestId} so the caller can correlate a late response with
     * the attempt that produced it - that correlation is the entire point of the
     * {@link FailureMode#DUPLICATE_RESPONSE} scenario.
     */
    @PostMapping("/authorize")
    public ResponseEntity<Map<String, Object>> authorize(
            @Valid @RequestBody AuthorizeRequest request, HttpServletResponse servletResponse) throws IOException {
        return switch (properties.getMode()) {
            case NORMAL -> sleepThenAuthorize(properties.getBaseLatency(), request);
            case SLOW -> sleepThenAuthorize(properties.getSlowLatency(), request);
            // Not "never respond": the processor does the work and answers long after any sane
            // client has given up. The money moved and the caller does not know - which is exactly
            // the situation payment_attempts.outcome = UNKNOWN exists to represent.
            case TIMEOUT -> sleepThenAuthorize(properties.getTimeoutLatency(), request);
            case HTTP_500 -> ResponseEntity.internalServerError().body(error(request, "PROCESSOR_ERROR"));
            case HTTP_429 -> ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header(HttpHeaders.RETRY_AFTER, String.valueOf(RETRY_AFTER_SECONDS))
                    .body(error(request, "RATE_LIMITED"));
            // Background noise: the fraction that fails is a 500, the rest behave like NORMAL.
            case INTERMITTENT -> ThreadLocalRandom.current().nextDouble() < properties.getIntermittentFailureRate()
                    ? ResponseEntity.internalServerError().body(error(request, "PROCESSOR_ERROR"))
                    : sleepThenAuthorize(properties.getBaseLatency(), request);
            case DUPLICATE_RESPONSE -> answerTwice(request, servletResponse);
            case OUTAGE -> {
                if (Instant.now().isBefore(outageEndsAt)) {
                    yield ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(error(request, "OUTAGE"));
                }
                // Over: recover by itself, so the caller's circuit breaker can be watched closing
                // without anyone touching the control plane.
                properties.setMode(FailureMode.NORMAL);
                yield sleepThenAuthorize(properties.getBaseLatency(), request);
            }
        };
    }

    /**
     * The first time a request id arrives: authorise, then cut the connection halfway through the
     * answer. Any later time: the same answer, whole.
     *
     * <p>How the cut works: declare the full Content-Length, send half, and ask Tomcat to close the
     * connection when the response ends. The caller then sees the body end early, a broken
     * connection mid-answer. The first version threw an exception after the half body instead,
     * expecting Tomcat to drop the connection; it didn't. Spring Boot's error handling appended its
     * own JSON to the committed response, and the caller got a complete, garbled body - which it
     * rightly called malformed, not broken. Found on the real stack, not by the tests.
     */
    private ResponseEntity<Map<String, Object>> answerTwice(
            AuthorizeRequest request, HttpServletResponse servletResponse) throws IOException {
        boolean firstDelivery = !answersByRequestId.containsKey(request.requestId());
        Map<String, Object> answer = answersByRequestId.computeIfAbsent(request.requestId(), id -> decide(request));
        if (!firstDelivery) {
            return ResponseEntity.ok(answer);
        }
        byte[] body = objectMapper.writeValueAsBytes(answer);
        servletResponse.setStatus(HttpStatus.OK.value());
        servletResponse.setContentType(MediaType.APPLICATION_JSON_VALUE);
        servletResponse.setContentLength(body.length);
        servletResponse.setHeader(HttpHeaders.CONNECTION, "close");
        servletResponse.getOutputStream().write(body, 0, body.length / 2);
        servletResponse.flushBuffer();
        log.info("DUPLICATE_RESPONSE: authorised {} and cut the answer off on purpose", request.requestId());
        // Nothing more: the response is committed, and returning null lets it end as it stands.
        return null;
    }

    /** TODO Phase 3 (Day 14): capture an existing authorisation. Same failure-mode switch. */
    @PostMapping("/capture")
    public ResponseEntity<Map<String, Object>> capture(@Valid @RequestBody CaptureRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
    }

    /** TODO Phase 3 (Day 14): refund a captured authorisation. Same failure-mode switch. */
    @PostMapping("/refund")
    public ResponseEntity<Map<String, Object>> refund(@Valid @RequestBody RefundRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
    }

    // ---------------------------------------------------------------------------------------
    // Control plane
    // ---------------------------------------------------------------------------------------

    /** What is this processor doing right now, and what else could it do? */
    @GetMapping("/control/mode")
    public Map<String, Object> currentMode() {
        return Map.of(
                "mode", properties.getMode().name(),
                "description", properties.getMode().description(),
                "available", java.util.Arrays.stream(FailureMode.values())
                        .collect(java.util.stream.Collectors.toMap(Enum::name, FailureMode::description)));
    }

    /**
     * Switch behaviour at runtime: {@code curl -X PUT 'localhost:8081/control/mode?mode=HTTP_500'}.
     *
     * <p>Spring converts the query parameter to the enum for you and returns 400 for an unknown
     * value, which is the behaviour you want - a typo in a test should fail the test, not silently
     * leave the processor in NORMAL and produce a green run that proved nothing.
     */
    @PutMapping("/control/mode")
    public ResponseEntity<Map<String, Object>> setMode(
            @RequestParam FailureMode mode,
            @RequestParam(required = false) Double failureRate,
            @RequestParam(name = "latency", required = false) String latencyParam) {
        // Optional levers, each rejected when out of range, for the same reason as an unknown mode:
        //   '...?mode=INTERMITTENT&failureRate=0.05'    '...?mode=SLOW&latency=1500ms'
        if (failureRate != null && (failureRate < 0.0 || failureRate > 1.0)) {
            return ResponseEntity.badRequest().body(Map.of("error", "failureRate must be between 0 and 1"));
        }
        // Parsed by hand: MVC binds a Duration parameter from ISO-8601 only ('PT1.5S'), and the
        // form everyone types - and the one application.yml uses - is '1500ms'. Both work here.
        Duration latency = null;
        if (latencyParam != null) {
            try {
                latency = DurationStyle.detectAndParse(latencyParam);
            } catch (IllegalArgumentException e) {
                return ResponseEntity.badRequest().body(Map.of("error", "latency is not a duration, e.g. 1500ms"));
            }
            if (latency.isNegative()) {
                return ResponseEntity.badRequest().body(Map.of("error", "latency must not be negative"));
            }
        }
        if (failureRate != null) {
            properties.setIntermittentFailureRate(failureRate);
        }
        if (latency != null) {
            properties.setSlowLatency(latency);
        }
        if (mode == FailureMode.OUTAGE) {
            outageEndsAt = Instant.now().plus(properties.getOutageDuration());
        }
        properties.setMode(mode);
        return ResponseEntity.ok(Map.of(
                "mode", mode.name(),
                "description", mode.description(),
                "intermittentFailureRate", properties.getIntermittentFailureRate(),
                "slowLatency", properties.getSlowLatency().toString()));
    }

    /** TODO Phase 3 (Day 17): reset counters, clear the in-memory stores, mode=NORMAL. */
    @PostMapping("/control/reset")
    public ResponseEntity<Void> reset() {
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
    }

    // ---------------------------------------------------------------------------------------
    // Behaviours
    // ---------------------------------------------------------------------------------------

    /**
     * Sleep, then approve (or occasionally decline). Thread.sleep on a request thread is the point:
     * a slow processor holds the CALLER's thread and connection for exactly this long.
     *
     * <p>A decline is a 200 with {@code status=DECLINED}: a business outcome, not a technical
     * failure. The caller must not retry it and its circuit breaker must not count it.
     */
    private ResponseEntity<Map<String, Object>> sleepThenAuthorize(Duration latency, AuthorizeRequest request) {
        try {
            Thread.sleep(latency);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(error(request, "INTERRUPTED"));
        }
        return ResponseEntity.ok(answersByRequestId.computeIfAbsent(request.requestId(), id -> decide(request)));
    }

    private Map<String, Object> decide(AuthorizeRequest request) {
        if (ThreadLocalRandom.current().nextDouble() < properties.getDeclineRate()) {
            return Map.of(
                    "requestId", request.requestId(),
                    "status", "DECLINED",
                    "declineCode", "INSUFFICIENT_FUNDS");
        }
        String authorizationId = "auth_" + UUID.randomUUID();
        authorizations.put(authorizationId, request);
        return Map.of(
                "requestId", request.requestId(),
                "authorizationId", authorizationId,
                "status", "APPROVED",
                "amount", request.amount(),
                "currency", request.currency());
    }

    private static Map<String, Object> error(AuthorizeRequest request, String code) {
        return Map.of("requestId", request.requestId(), "error", code);
    }

    /**
     * Requests. Records, because they are immutable data at a service boundary and Java 21 makes
     * that a one-liner. {@code amount} is minor units, as everywhere else in this project.
     */
    public record AuthorizeRequest(
            @NotBlank String requestId,
            @NotBlank String paymentMethodToken,
            @Min(1) long amount,
            @NotBlank String currency) {
    }

    public record CaptureRequest(@NotBlank String requestId, @NotBlank String authorizationId) {
    }

    public record RefundRequest(
            @NotBlank String requestId, @NotBlank String authorizationId, @Min(1) long amount) {
    }
}
